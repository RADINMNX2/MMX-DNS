/// Native resource-optimization layer of the Zero-Impact Background Engine (ZIBE).
/// Handles POSIX thread affinity pinning and thread niceness adjustments.

use once_cell::sync::Lazy;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, Ordering};

// Struct to hold thread registration info for background worker thread
struct ThreadInfo {
    pthread: libc::pthread_t,
    tid: libc::id_t,
}

// Global static to keep track of the background packet-forwarding thread handles
static PACKET_THREAD: Lazy<Mutex<Option<ThreadInfo>>> = Lazy::new(|| Mutex::new(None));

// Atomic flag indicating if ZIBE optimization is active/requested
static ZIBE_OPTIMIZATION_ACTIVE: AtomicBool = AtomicBool::new(false);

/// Registers the active packet-forwarding thread handle and kernel TID
pub fn register_packet_thread(pthread: libc::pthread_t, tid: libc::id_t) {
    let mut guard = PACKET_THREAD.lock().unwrap();
    *guard = Some(ThreadInfo { pthread, tid });
    log::info!("ZIBE: Registered background packet-forwarding thread (pthread_t={:?}, tid={})", pthread, tid);
    
    // If the optimization flag was already enabled before the thread started, apply it immediately
    if ZIBE_OPTIMIZATION_ACTIVE.load(Ordering::SeqCst) {
        log::info!("ZIBE: Optimization is pre-enabled. Applying immediately to new thread.");
        if let Err(e) = apply_optimization_internal(pthread, tid) {
            log::error!("ZIBE: Failed to apply pre-enabled optimization: {}", e);
        }
    }
}

/// Applies ZIBE CPU pinning and scheduling priority optimizations to the registered thread,
/// and sets the global optimization active flag.
pub fn apply_zibe_optimization() -> Result<(), String> {
    ZIBE_OPTIMIZATION_ACTIVE.store(true, Ordering::SeqCst);
    
    let guard = PACKET_THREAD.lock().unwrap();
    if let Some(ref info) = *guard {
        apply_optimization_internal(info.pthread, info.tid)
    } else {
        log::warn!("ZIBE: Optimization requested but no active packet-forwarding thread registered yet.");
        Ok(())
    }
}

/// Disables ZIBE optimization or resets CPU affinity and niceness (reverts to default) if required
pub fn reset_zibe_optimization() -> Result<(), String> {
    ZIBE_OPTIMIZATION_ACTIVE.store(false, Ordering::SeqCst);
    
    let guard = PACKET_THREAD.lock().unwrap();
    if let Some(ref info) = *guard {
        unsafe {
            // Restore default affinity (all cores)
            let mut cpuset: libc::cpu_set_t = std::mem::zeroed();
            libc::CPU_ZERO(&mut cpuset);
            // Typically octa-core mobile chips have 8 CPUs. Let's set 0-7.
            for cpu in 0..8 {
                libc::CPU_SET(cpu, &mut cpuset);
            }
            
            let res = libc::pthread_setaffinity_np(
                info.pthread,
                std::mem::size_of::<libc::cpu_set_t>(),
                &cpuset,
            );
            if res != 0 {
                return Err(format!("Failed to reset CPU affinity: error {}", res));
            }
            
            // Restore default priority (0)
            let res_prio = libc::setpriority(libc::PRIO_PROCESS, info.tid as libc::id_t, 0);
            if res_prio != 0 {
                return Err(format!("Failed to reset thread priority: error {}", std::io::Error::last_os_error()));
            }
            
            log::info!("ZIBE: Successfully reset CPU affinity and priority to system defaults.");
        }
    }
    Ok(())
}

/// Internal core implementation that binds affinity and adjustments using POSIX APIs.
fn apply_optimization_internal(pthread: libc::pthread_t, tid: libc::id_t) -> Result<(), String> {
    unsafe {
        // 1. CPU Affinity Pinning (ARM big.LITTLE Binding)
        // Initialize the CPU set and bind only to CPUs 0, 1, 2, and 3 (LITTLE/energy-efficient cores)
        let mut cpuset: libc::cpu_set_t = std::mem::zeroed();
        libc::CPU_ZERO(&mut cpuset);
        for cpu in 0..4 {
            libc::CPU_SET(cpu, &mut cpuset);
        }
        
        log::info!("ZIBE: Binding thread {:?} to LITTLE CPU cores (0-3)", pthread);
        let res_affinity = libc::pthread_setaffinity_np(
            pthread,
            std::mem::size_of::<libc::cpu_set_t>(),
            &cpuset,
        );
        
        if res_affinity != 0 {
            return Err(format!("pthread_setaffinity_np failed with error code: {}", res_affinity));
        }
        log::info!("ZIBE: Thread affinity successfully bound to CPU cores 0-3.");
        
        // 2. Thread Scheduling Priority (Niceness)
        // Execute setpriority(PRIO_PROCESS, tid, 19) to set priority of background packet-forwarding thread to 19 (lowest)
        log::info!("ZIBE: Adjusting niceness of thread TID={} to 19 (lowest priority)", tid);
        let res_priority = libc::setpriority(libc::PRIO_PROCESS, tid as libc::id_t, 19);
        if res_priority != 0 {
            return Err(format!("setpriority failed with error: {}", std::io::Error::last_os_error()));
        }
        log::info!("ZIBE: Thread priority successfully adjusted to niceness 19.");
    }
    Ok(())
}
