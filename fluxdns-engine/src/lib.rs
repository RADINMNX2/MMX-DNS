/// FluxDNS Native Engine - JNI bridge and asynchronous TUN polling reactor.

mod packet;
mod resolver;
pub mod protocols;
pub mod dns;
pub mod sockets;
pub mod fec;
pub mod perf;
pub mod telemetry;
pub mod queue;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong};
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

pub(crate) struct CellularMetricsState {
    pub rsrp: std::sync::atomic::AtomicI32,
    pub sinr: std::sync::atomic::AtomicI32,
    pub cell_id: std::sync::atomic::AtomicI64,
}

pub(crate) static CELLULAR_METRICS: Lazy<CellularMetricsState> = Lazy::new(|| CellularMetricsState {
    rsrp: std::sync::atomic::AtomicI32::new(-140),
    sinr: std::sync::atomic::AtomicI32::new(-20),
    cell_id: std::sync::atomic::AtomicI64::new(-1),
});

pub(crate) static TOKIO_HANDLE: Lazy<std::sync::RwLock<Option<tokio::runtime::Handle>>> = Lazy::new(|| std::sync::RwLock::new(None));

/// Thread-safe helper to spawn a future onto the global Tokio runtime from any (including JVM JNI) thread context.
pub fn spawn_on_global_runtime<F>(future: F)
where
    F: std::future::Future<Output = ()> + Send + 'static,
{
    if let Ok(reader) = TOKIO_HANDLE.read() {
        if let Some(handle) = reader.as_ref() {
            handle.spawn(future);
            return;
        }
    }
    log::warn!("Global Tokio Handle not initialized yet. Cannot spawn task.");
}

static ENGINE: Lazy<Mutex<EngineState>> = Lazy::new(|| Mutex::new(EngineState {
    shutdown_tx: None,
    queries_resolved: std::sync::atomic::AtomicU32::new(0),
    primary_dns: String::new(),
    secondary_dns: String::new(),
    protocol: String::new(),
}));

#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_updateCellularMetricsNative(
    _env: JNIEnv,
    _class: JClass,
    rsrp: jint,
    sinr: jint,
    cell_id: jlong,
) {
    CELLULAR_METRICS.rsrp.store(rsrp, std::sync::atomic::Ordering::SeqCst);
    CELLULAR_METRICS.sinr.store(sinr, std::sync::atomic::Ordering::SeqCst);
    CELLULAR_METRICS.cell_id.store(cell_id, std::sync::atomic::Ordering::SeqCst);
    log::info!(
        "AetherCell Telemetry Updated: RSRP = {} dBm, SINR = {} dB, Cell ID = {}",
        rsrp,
        sinr,
        cell_id
    );
    
    // Dynamically trigger the Signal-Aware Adaptive FEC and Handover DNS Flusher loop
    telemetry::adaptive_control::process_telemetry_update(rsrp, sinr, cell_id);
}

#[no_mangle]
pub static mut JVM: Option<jni::JavaVM> = None;

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: jni::JavaVM, _reserved: *mut libc::c_void) -> jint {
    unsafe {
        JVM = Some(vm);
    }
    jni::sys::JNI_VERSION_1_6
}


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
        // Register the background packet-forwarding thread for resource optimizations
        let pthread = unsafe { libc::pthread_self() };
        let tid = unsafe { libc::syscall(libc::SYS_gettid) as libc::id_t };
        perf::affinity::register_packet_thread(pthread, tid);

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
            
            // Store active runtime handle globally to enable JNI threads to safely spawn tasks on the Tokio loop
            if let Ok(mut writer) = TOKIO_HANDLE.write() {
                *writer = Some(tokio::runtime::Handle::current());
            }
            
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

            // Start RRC connection pinning daemon to maintain active RRC_CONNECTED state
            telemetry::rrc_pin::start_daemon(primary_dns_clone.clone());

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
pub extern "system" fn Java_com_example_service_FluxDnsEngine_applyZibeOptimizationNative(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    match perf::affinity::apply_zibe_optimization() {
        Ok(_) => {
            log::info!("ZIBE: Successfully applied CPU pinning and scheduling priority optimizations.");
            jni::sys::JNI_TRUE
        }
        Err(e) => {
            log::error!("ZIBE: Failed to apply optimization: {}", e);
            jni::sys::JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_resetZibeOptimizationNative(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    match perf::affinity::reset_zibe_optimization() {
        Ok(_) => {
            log::info!("ZIBE: Successfully reset CPU affinity and scheduling priority.");
            jni::sys::JNI_TRUE
        }
        Err(e) => {
            log::error!("ZIBE: Failed to reset optimization: {}", e);
            jni::sys::JNI_FALSE
        }
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

#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_resolveQueryNative(
    mut env: JNIEnv,
    _class: JClass,
    query: jni::sys::jbyteArray,
    primary_dns: JString,
    secondary_dns: JString,
    protocol: JString,
) -> jni::sys::jbyteArray {
    let query_array = unsafe { jni::objects::JByteArray::from_raw(query) };
    let query_bytes: Vec<u8> = match env.convert_byte_array(&query_array) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let primary: String = match env.get_string(&primary_dns) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let secondary: String = match env.get_string(&secondary_dns) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let proto: String = match env.get_string(&protocol) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let rt = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build() {
            Ok(r) => r,
            Err(_) => return std::ptr::null_mut(),
        };

    let resolved = rt.block_on(async {
        resolver::resolve_query(&query_bytes, &primary, &secondary, &proto).await
    });

    match resolved {
        Some(res_vec) => {
            match env.byte_array_from_slice(&res_vec) {
                Ok(java_arr) => java_arr.as_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_tuneSocketNative(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jint {
    match sockets::tuning::tune_socket_fd(fd as RawFd) {
        Ok(()) => 0,
        Err(e) => {
            let os_err = e.raw_os_error().unwrap_or(0);
            match os_err {
                libc::EBADF => -1,      // Bad file descriptor
                libc::EACCES => -2,     // Permission denied
                libc::ENOPROTOOPT => -3, // Option not supported on this protocol
                libc::ENOTSOCK => -4,    // File descriptor is not a socket
                libc::EINVAL => -5,     // Invalid parameters
                _ => -99,               // Other/unclassified system error
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_enforceDfNative(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jint {
    match sockets::mtu::enforce_df_flag(fd as RawFd) {
        Ok(()) => 0,
        Err(e) => {
            let os_err = e.raw_os_error().unwrap_or(0);
            match os_err {
                libc::EBADF => -1,      // Bad file descriptor
                libc::EACCES => -2,     // Permission denied
                libc::ENOPROTOOPT => -3, // Option not supported on this protocol
                libc::ENOTSOCK => -4,    // File descriptor is not a socket
                libc::EINVAL => -5,     // Invalid parameters
                _ => -99,               // Other/unclassified system error
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_getTrackedMtuNative(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    sockets::mtu::GLOBAL_MTU_MANAGER.get_mtu() as jint
}

#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_setTrackedMtuNative(
    _env: JNIEnv,
    _class: JClass,
    mtu: jint,
) {
    sockets::mtu::GLOBAL_MTU_MANAGER.update_mtu(mtu as u32);
}

#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_queryKernelMtuNative(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jint {
    match sockets::mtu::GLOBAL_MTU_MANAGER.query_kernel_pmtu(fd as RawFd) {
        Some(mtu) => mtu as jint,
        None => -1,
    }
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
        Ok(afd) => std::sync::Arc::new(afd),
        Err(e) => {
            log::error!("Failed to register TUN file descriptor with Tokio AsyncFd: {:?}", e);
            unsafe { libc::close(fd) };
            return Err(e.into());
        }
    };

    // Buffer to support asynchronous writes from DNS resolution worker threads with Zero-Allocation PoolGuards
    let (write_tx, mut write_rx) = mpsc::channel::<(perf::memory_pool::PoolGuard, usize)>(1024);
    let mut read_buf = [0u8; 32768];
    
    // Instantiate high-performance 10:2 Jitter Stabilization Buffer
    let jitter_buffer = std::sync::Arc::new(fec::decoder::JitterStabilizationBuffer::new(10, 2));

    log::info!("Duplex AsyncFd reactor successfully active on Raw TUN FD={}", fd);

    // Inner helper to write packets to TUN asynchronously
    async fn write_to_tun(async_fd: &std::sync::Arc<tokio::io::unix::AsyncFd<TunFd>>, pkt: &[u8]) {
        match async_fd.writable().await {
            Ok(mut guard) => {
                let _ = guard.try_io(|inner| {
                    let res = unsafe {
                        libc::write(
                            inner.as_raw_fd(),
                            pkt.as_ptr() as *const libc::c_void,
                            pkt.len(),
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

    // Spawn a dedicated pacing and AQM (CoDel) controller task for non-essential traffic
    let (paced_tx, mut paced_rx) = mpsc::channel::<(perf::memory_pool::PoolGuard, usize)>(1024);
    let async_fd_clone = async_fd.clone();
    
    tokio::spawn(async move {
        let mut queue_manager = queue::codel::QueueManager::new();
        loop {
            tokio::select! {
                res = paced_rx.recv() => {
                    match res {
                        Some((guard, len)) => {
                            queue_manager.enqueue(guard, len);
                        }
                        None => {
                            log::info!("Paced queue channel closed. Terminating paced task.");
                            break;
                        }
                    }
                }
                
                _ = async {
                    if queue_manager.is_empty() {
                        std::future::pending::<()>().await;
                    }
                    if let Some(wait_duration) = queue_manager.get_tbf_wait_time() {
                        tokio::time::sleep(wait_duration).await;
                    }
                }, if !queue_manager.is_empty() => {
                    queue_manager.update_rate_from_telemetry();
                    if let Some(packet) = queue_manager.dequeue_codel() {
                        write_to_tun(&async_fd_clone, &packet.guard[..packet.len]).await;
                    }
                }
            }
        }
    });

    loop {
        tokio::select! {
            // 1. Graceful Shutdown listener
            _ = shutdown_rx.recv() => {
                log::info!("Graceful shutdown signal triggered inside native loop.");
                break;
            }
            
            // 2. Outbound Packet Writer
            Some((reply_guard, reply_len)) = write_rx.recv() => {
                crate::telemetry::rrc_pin::update_activity();
                let out_packet = &reply_guard[..reply_len];
                
                // Categorize packet
                let mut is_priority = true;
                if let Some(parsed) = packet::parse_packet(out_packet) {
                    if parsed.protocol == packet::IpProtocol::Tcp {
                        is_priority = false; // Non-essential TCP/HTTP payloads are paced/throttled
                    }
                }
                
                // Fast path: if FEC magic is present, use Jitter Stabilization Buffer.
                // Otherwise, write the slice directly to the TUN interface from the pre-allocated pool buffer with ZERO allocations.
                let is_fec = out_packet.len() >= fec::AetherFecHeader::SIZE 
                    && u16::from_be_bytes([out_packet[0], out_packet[1]]) == fec::AetherFecHeader::MAGIC;
                
                if is_fec {
                    is_priority = true;
                }
                
                if is_priority {
                    if is_fec {
                        if let Some(reconstructed_list) = jitter_buffer.insert_packet(out_packet.to_vec()) {
                            for pkt in reconstructed_list {
                                write_to_tun(&async_fd, &pkt).await;
                            }
                        }
                    } else {
                        write_to_tun(&async_fd, out_packet).await;
                    }
                } else {
                    // Route non-essential TCP/HTTP payloads into the paced queue
                    if let Err(e) = paced_tx.send((reply_guard, reply_len)).await {
                        log::error!("Failed to forward non-essential packet to paced queue: {:?}", e);
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
                                
                                crate::telemetry::rrc_pin::update_activity();
                                let packet_data = &read_buf[..bytes_read];
                                if let Some(parsed) = packet::parse_packet(packet_data) {
                                    // Filter exclusively for DNS Query (UDP Port 53)
                                    if parsed.dst_port == 53 {
                                        // Zero-Allocation and Zero-Copy: capture parsed components in-place in a pooled block
                                        let pooled_task = perf::memory_pool::PooledTask::new(
                                            parsed.src_ip,
                                            parsed.dst_ip,
                                            parsed.src_port,
                                            parsed.dst_port,
                                            parsed.payload,
                                        );
                                        
                                        let write_tx_clone = write_tx.clone();
                                        let primary = primary_dns.clone();
                                        let secondary = secondary_dns.clone();
                                        let proto = protocol.clone();
                                        
                                        // Spawn lightweight task to resolve the query concurrently and avoid blocking the loop
                                        tokio::spawn(async move {
                                            if let Some(reply) = dns::racer::race_query(
                                                pooled_task.payload(),
                                                &primary,
                                                &secondary,
                                                &proto
                                             ).await {
                                                 // Increment atomic queries resolved count
                                                 {
                                                     let engine_state = ENGINE.lock().unwrap();
                                                     engine_state.queries_resolved.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                                                 }
                                                 
                                                 // Package DNS response inside a custom IP/UDP frame within a pre-allocated pool buffer
                                                 let mut reply_guard = perf::memory_pool::MEMORY_POOL.acquire();
                                                 if let Some(reply_len) = packet::build_reply_packet_in_place(
                                                     &reply,
                                                     pooled_task.src_ip(),
                                                     pooled_task.dst_ip(),
                                                     pooled_task.src_port,
                                                     pooled_task.dst_port,
                                                     &mut *reply_guard,
                                                 ) {
                                                     let _ = write_tx_clone.send((reply_guard, reply_len)).await;
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
