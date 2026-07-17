/// FluxDNS Native Engine - JNI bridge and asynchronous TUN polling reactor.

mod packet;
mod resolver;
pub mod protocols;
pub mod dns;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint};
use std::os::unix::io::RawFd;
use std::sync::Mutex;
use once_cell::sync::Lazy;
use tokio::sync::mpsc;

struct EngineState {
    shutdown_tx: Option<mpsc::Sender<()>>,
    queries_resolved: std::sync::atomic::AtomicU32,
    primary_dns: String,
    secondary_dns: String,
    protocol: String,
}

static ENGINE: Lazy<Mutex<EngineState>> = Lazy::new(|| Mutex::new(EngineState {
    shutdown_tx: None,
    queries_resolved: std::sync::atomic::AtomicU32::new(0),
    primary_dns: String::new(),
    secondary_dns: String::new(),
    protocol: String::new(),
}));

#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_startEngine(
    mut env: JNIEnv,
    _class: JClass,
    tun_fd: jint,
    primary_dns: JString,
    secondary_dns: JString,
    protocol: JString,
) -> jboolean {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("FluxDnsEngineNative")
    );
    
    log::info!("Native engine start requested with FD={}", tun_fd);
    
    let primary: String = match env.get_string(&primary_dns) {
        Ok(s) => s.into(),
        Err(_) => return jni::sys::JNI_FALSE,
    };
    
    let secondary: String = match env.get_string(&secondary_dns) {
        Ok(s) => s.into(),
        Err(_) => return jni::sys::JNI_FALSE,
    };
    
    let proto: String = match env.get_string(&protocol) {
        Ok(s) => s.into(),
        Err(_) => return jni::sys::JNI_FALSE,
    };

    let mut engine = ENGINE.lock().unwrap();
    if engine.shutdown_tx.is_some() {
        log::warn!("Native engine is already running!");
        return jni::sys::JNI_TRUE;
    }

    engine.primary_dns = primary.clone();
    engine.secondary_dns = secondary.clone();
    engine.protocol = proto.clone();
    engine.queries_resolved.store(0, std::sync::atomic::Ordering::SeqCst);

    let (shutdown_tx, mut shutdown_rx) = mpsc::channel::<()>(1);
    engine.shutdown_tx = Some(shutdown_tx);

    // Duplicate TUN file descriptor to prevent JNI memory garbage collection from closing it prematurely
    let duplicated_fd = unsafe { libc::dup(tun_fd) };
    if duplicated_fd < 0 {
        log::error!("Failed to duplicate TUN file descriptor");
        return jni::sys::JNI_FALSE;
    }

    // Spawn dedicated OS thread to execute the Tokio runtime and avoid JVM thread blocking
    let primary_dns_clone = primary.clone();
    let secondary_dns_clone = secondary.clone();
    let proto_clone = proto.clone();

    std::thread::spawn(move || {
        let rt = match tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build() {
                Ok(r) => r,
                Err(e) => {
                    log::error!("Failed to create native Tokio runtime: {:?}", e);
                    unsafe { libc::close(duplicated_fd) };
                    return;
                }
            };

        rt.block_on(async move {
            log::info!("Native Tokio runtime successfully built. Launching packet reader duplex loop...");
            
            // Start lock-free DNS Cache janitor and proactive prefetcher
            dns::cache::DnsCache::start_janitor_and_prefetcher(
                dns::cache::GLOBAL_DNS_CACHE.clone(),
                primary_dns_clone.clone(),
                secondary_dns_clone.clone(),
                proto_clone.clone(),
            );

            // Start predictive gaming matchmaking pre-fetch daemon
            dns::prefetch::start_predictive_prefetcher(
                primary_dns_clone.clone(),
                secondary_dns_clone.clone(),
                proto_clone.clone(),
            );

            if let Err(e) = run_async_loop(
                duplicated_fd,
                primary_dns_clone,
                secondary_dns_clone,
                proto_clone,
                &mut shutdown_rx
            ).await {
                log::error!("Error in native packet polling loop: {:?}", e);
            }
            log::info!("Native async packet polling loop terminated.");
        });
    });

    jni::sys::JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_stopEngine(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut engine = ENGINE.lock().unwrap();
    if let Some(tx) = engine.shutdown_tx.take() {
        log::info!("Signaling native packet loop to shutdown...");
        let _ = tx.blocking_send(());
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_configureEngine(
    mut env: JNIEnv,
    _class: JClass,
    primary_dns: JString,
    secondary_dns: JString,
    protocol: JString,
) -> jboolean {
    let primary: String = match env.get_string(&primary_dns) {
        Ok(s) => s.into(),
        Err(_) => return jni::sys::JNI_FALSE,
    };
    
    let secondary: String = match env.get_string(&secondary_dns) {
        Ok(s) => s.into(),
        Err(_) => return jni::sys::JNI_FALSE,
    };
    
    let proto: String = match env.get_string(&protocol) {
        Ok(s) => s.into(),
        Err(_) => return jni::sys::JNI_FALSE,
    };

    let mut engine = ENGINE.lock().unwrap();
    engine.primary_dns = primary;
    engine.secondary_dns = secondary;
    engine.protocol = proto;
    log::info!("Native engine hot-reload: DNS servers configured to ({}, {}), Protocol={}", engine.primary_dns, engine.secondary_dns, engine.protocol);
    
    jni::sys::JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_getQueriesResolved(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let engine = ENGINE.lock().unwrap();
    engine.queries_resolved.load(std::sync::atomic::Ordering::SeqCst) as jint
}

/// Asynchronous duplex network I/O loop using non-blocking Tokio AsyncFd to read/write from/to the TUN.
async fn run_async_loop(
    fd: RawFd,
    primary_dns: String,
    secondary_dns: String,
    protocol: String,
    shutdown_rx: &mut mpsc::Receiver<()>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    use std::os::unix::io::AsRawFd;
    use tokio::io::unix::AsyncFd;

    struct TunFd(RawFd);
    impl AsRawFd for TunFd {
        fn as_raw_fd(&self) -> RawFd {
            self.0
        }
    }

    let async_fd = match AsyncFd::new(TunFd(fd)) {
        Ok(afd) => afd,
        Err(e) => {
            log::error!("Failed to register TUN file descriptor with Tokio AsyncFd: {:?}", e);
            unsafe { libc::close(fd) };
            return Err(e.into());
        }
    };

    // Buffer to support asynchronous writes from DNS resolution worker threads
    let (write_tx, mut write_rx) = mpsc::channel::<Vec<u8>>(1024);
    let mut read_buf = [0u8; 32768];
    
    log::info!("Duplex AsyncFd reactor successfully active on Raw TUN FD={}", fd);

    loop {
        tokio::select! {
            // 1. Graceful Shutdown listener
            _ = shutdown_rx.recv() => {
                log::info!("Graceful shutdown signal triggered inside native loop.");
                break;
            }
            
            // 2. Outbound Packet Writer
            Some(out_packet) = write_rx.recv() => {
                match async_fd.writable().await {
                    Ok(mut guard) => {
                        let _ = guard.try_io(|inner| {
                            let res = unsafe {
                                libc::write(
                                    inner.as_raw_fd(),
                                    out_packet.as_ptr() as *const libc::c_void,
                                    out_packet.len(),
                                )
                            };
                            if res < 0 {
                                Err(std::io::Error::last_os_error())
                            } else {
                                Ok(res as usize)
                            }
                        });
                    }
                    Err(e) => {
                        log::error!("Failed to obtain writable guard on AsyncFd TUN: {:?}", e);
                    }
                }
            }
            
            // 3. Inbound Packet Reader
            read_guard_res = async_fd.readable() => {
                match read_guard_res {
                    Ok(mut guard) => {
                        match guard.try_io(|inner| {
                            let res = unsafe {
                                libc::read(
                                    inner.as_raw_fd(),
                                    read_buf.as_mut_ptr() as *mut libc::c_void,
                                    read_buf.len(),
                                )
                            };
                            if res < 0 {
                                Err(std::io::Error::last_os_error())
                            } else {
                                Ok(res as usize)
                            }
                        }) {
                            Ok(Ok(bytes_read)) => {
                                if bytes_read == 0 {
                                    log::info!("AsyncFd TUN returned EOF.");
                                    break;
                                }
                                
                                let packet_data = &read_buf[..bytes_read];
                                if let Some(parsed) = packet::parse_packet(packet_data) {
                                    // Filter exclusively for DNS Query (UDP Port 53)
                                    if parsed.dst_port == 53 {
                                        let query_payload = parsed.payload.to_vec();
                                        let src_ip = parsed.src_ip.to_vec();
                                        let dst_ip = parsed.dst_ip.to_vec();
                                        let src_port = parsed.src_port;
                                        let dst_port = parsed.dst_port;
                                        
                                        let write_tx_clone = write_tx.clone();
                                        let primary = primary_dns.clone();
                                        let secondary = secondary_dns.clone();
                                        let proto = protocol.clone();
                                        
                                        // Spawn lightweight task to resolve the query concurrently and avoid blocking the loop
                                        tokio::spawn(async move {
                                            if let Some(reply) = dns::racer::race_query(
                                                &query_payload,
                                                &primary,
                                                &secondary,
                                                &proto
                                            ).await {
                                                // Increment atomic queries resolved count
                                                let engine_state = ENGINE.lock().unwrap();
                                                engine_state.queries_resolved.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                                                drop(engine_state);
                                                
                                                // Package DNS response inside a custom IP/UDP frame
                                                if let Some(reply_frame) = packet::build_reply_packet(
                                                    &reply,
                                                    &src_ip,
                                                    &dst_ip,
                                                    src_port,
                                                    dst_port
                                                ) {
                                                    let _ = write_tx_clone.send(reply_frame).await;
                                                }
                                            }
                                        });
                                    }
                                }
                            }
                            Ok(Err(e)) => {
                                log::error!("Read error on AsyncFd TUN: {:?}", e);
                                break;
                            }
                            Err(_would_block) => {
                                // Spurious wake, continue polling
                                continue;
                            }
                        }
                    }
                    Err(e) => {
                        log::error!("Failed to obtain readable guard on AsyncFd TUN: {:?}", e);
                        break;
                    }
                }
            }
        }
    }

    // Safely close raw file descriptor on loop completion
    unsafe { libc::close(fd) };
    Ok(())
}
