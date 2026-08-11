use jni::objects::{JClass, JObject, JString, JObjectArray};
use jni::JNIEnv;
use jni::sys::{jint, jstring, jboolean};
use std::os::fd::FromRawFd;
use std::fs::File;
use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use serde::{Deserialize, Serialize};

static ENGINE_RUNNING: AtomicBool = AtomicBool::new(false);

lazy_static::lazy_static! {
    static ref STATS: Arc<Mutex<NetworkStatistics>> = Arc::new(Mutex::new(NetworkStatistics {
        total_queries: 0,
        cache_hit_rate: 0.0,
        average_latency: 0.0,
    }));
}

#[derive(Serialize)]
struct NetworkStatistics {
    total_queries: i64,
    cache_hit_rate: f64,
    average_latency: f64,
}

#[derive(Serialize)]
struct BenchmarkResult {
    minMs: f64,
    avgMs: f64,
    medianMs: f64,
    p95Ms: f64,
    p99Ms: f64,
    successRate: f64,
    timeoutRate: f64,
    stabilityScore: f64,
}

#[derive(Serialize)]
struct ResolverDecision {
    primaryResolver: String,
    secondaryResolver: String,
    score: i32,
    confidence: String,
    reason: String,
}

#[no_mangle]
pub extern "C" fn Java_com_example_service_NeonDnsNative_startEngineNative(
    mut _env: JNIEnv,
    _class: JClass,
    tun_fd: jint,
) {
    if ENGINE_RUNNING.swap(true, Ordering::SeqCst) {
        return;
    }

    let fd = tun_fd as std::os::unix::io::RawFd;
    
    // We clone the FD or just take ownership. In JNI, usually the JVM still holds the FD,
    // so we should be careful. We'll duplicate it so we don't accidentally close the JVM's copy if we panic.
    let duplicated_fd = unsafe { libc::dup(fd) };
    if duplicated_fd < 0 {
        return;
    }

    thread::spawn(move || {
        let mut tun_file = unsafe { File::from_raw_fd(duplicated_fd) };
        let mut buf = [0u8; 4096];

        while ENGINE_RUNNING.load(Ordering::Relaxed) {
            // Simplified TUN packet read
            // In a real app we would use non-blocking I/O or select/poll
            // We'll just do a blocking read for the skeleton
            // and maybe timeout or use libc::poll.
            
            // To prevent blocking forever and not being able to stop, we should use poll:
            let mut fds = libc::pollfd {
                fd: duplicated_fd,
                events: libc::POLLIN,
                revents: 0,
            };

            let ret = unsafe { libc::poll(&mut fds, 1, 1000) };
            if ret > 0 && (fds.revents & libc::POLLIN) != 0 {
                if let Ok(size) = tun_file.read(&mut buf) {
                    if size > 0 {
                        // Just increment stats for demonstration
                        if let Ok(mut stats) = STATS.lock() {
                            stats.total_queries += 1;
                        }
                        
                        // Parse packet, check if it's DNS UDP port 53, etc.
                        // Forward to upstream, get response, write back.
                    }
                }
            }
        }
        // File is dropped and closed here.
    });
}

#[no_mangle]
pub extern "C" fn Java_com_example_service_NeonDnsNative_stopEngineNative(
    mut _env: JNIEnv,
    _class: JClass,
) {
    ENGINE_RUNNING.store(false, Ordering::SeqCst);
}

#[no_mangle]
pub extern "C" fn Java_com_example_service_NeonDnsNative_benchmarkResolversNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    ips: JObjectArray<'local>,
    transports: JObjectArray<'local>,
) -> JString<'local> {
    
    // Create a dummy JSON response since this requires real networking
    let json_str = r#"{
        "194.146.68.68": {
            "minMs": 8.7,
            "avgMs": 9.1,
            "medianMs": 8.9,
            "p95Ms": 12.0,
            "p99Ms": 15.0,
            "successRate": 100.0,
            "timeoutRate": 0.0,
            "stabilityScore": 99.8
        },
        "1.1.1.1": {
            "minMs": 11.2,
            "avgMs": 12.5,
            "medianMs": 12.0,
            "p95Ms": 18.0,
            "p99Ms": 25.0,
            "successRate": 99.9,
            "timeoutRate": 0.1,
            "stabilityScore": 98.5
        }
    }"#;
    
    env.new_string(json_str).unwrap()
}

#[no_mangle]
pub extern "C" fn Java_com_example_service_NeonDnsNative_selectBestResolverNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    candidates: JObjectArray<'local>,
    network_type: JString<'local>,
) -> JString<'local> {
    
    let decision = ResolverDecision {
        primaryResolver: "194.146.68.68".to_string(),
        secondaryResolver: "1.1.1.1".to_string(),
        score: 97,
        confidence: "High".to_string(),
        reason: "Lowest latency and highest stability on current network.".to_string(),
    };
    
    let json_str = serde_json::to_string(&decision).unwrap_or_else(|_| "{}".to_string());
    env.new_string(json_str).unwrap()
}

#[no_mangle]
pub extern "C" fn Java_com_example_service_NeonDnsNative_getStatisticsNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    
    let stats = STATS.lock().unwrap();
    let json_str = serde_json::to_string(&*stats).unwrap_or_else(|_| "{}".to_string());
    env.new_string(json_str).unwrap()
}
