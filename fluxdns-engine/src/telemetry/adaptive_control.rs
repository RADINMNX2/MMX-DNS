/// Signal-Aware Adaptive FEC and Handover DNS Flusher Controller

use std::sync::atomic::{AtomicI32, AtomicI64, AtomicUsize, Ordering};
use once_cell::sync::Lazy;

// --- Global Configurable FEC Parameters ---
// These are dynamically adjusted by the adaptive control loop based on JNI cellular telemetry.
pub static ACTIVE_FEC_K: AtomicUsize = AtomicUsize::new(10); // Number of data packets
pub static ACTIVE_FEC_M: AtomicUsize = AtomicUsize::new(2);  // Number of parity packets

// --- Handover State Tracker ---
// Tracks the last registered Cell ID to detect base station handovers.
pub static LAST_CELL_ID: AtomicI64 = AtomicI64::new(-1);

/// Initialize/Configures the adaptive FEC parameters based on cellular telemetry.
/// Maps SINR and RSRP values directly to Reed-Solomon FEC redundancy.
pub fn process_telemetry_update(rsrp: i32, sinr: i32, cell_id: i64) {
    // 1. Adaptive FEC Redundancy Loop
    // SINR (Signal-to-Interference-plus-Noise Ratio) and RSRP (Reference Signal Received Power)
    // are the primary indicators of cellular channel quality and edge-of-cell conditions.
    if sinr < 5 {
        // Weak or Noisy Signal (SINR < 5dB): High channel packet loss expected.
        // Scale up FEC redundancy to 25% (e.g., K = 8, M = 2) to actively reconstruct drops.
        ACTIVE_FEC_K.store(8, Ordering::SeqCst);
        ACTIVE_FEC_M.store(2, Ordering::SeqCst);
        log::warn!(
            "Adaptive FEC: Weak signal detected (SINR = {} dB). Scaling up redundancy to 25% (K=8, M=2) for robust error recovery.",
            sinr
        );
    } else if sinr > 20 {
        // Strong Signal (SINR > 20dB): Low packet loss expected.
        // Scale down FEC redundancy to 5% (e.g., K = 20, M = 1) to conserve processing power and cellular bandwidth.
        ACTIVE_FEC_K.store(20, Ordering::SeqCst);
        ACTIVE_FEC_M.store(1, Ordering::SeqCst);
        log::info!(
            "Adaptive FEC: Strong signal detected (SINR = {} dB). Scaling down redundancy to 5% (K=20, M=1) to optimize overhead.",
            sinr
        );
    } else {
        // Normal/Average cellular conditions: Balanced 15% - 20% redundancy.
        ACTIVE_FEC_K.store(10, Ordering::SeqCst);
        ACTIVE_FEC_M.store(2, Ordering::SeqCst);
        log::info!(
            "Adaptive FEC: Normal signal conditions (SINR = {} dB). Operating at baseline 20% redundancy (K=10, M=2).",
            sinr
        );
    }

    // 2. Cell ID Handover Detection
    let old_cell_id = LAST_CELL_ID.swap(cell_id, Ordering::SeqCst);
    if old_cell_id != -1 && cell_id != -1 && old_cell_id != cell_id {
        log::warn!(
            "Handover Detected! Cellular Base Station changed from Cell ID {} to Cell ID {}.",
            old_cell_id,
            cell_id
        );
        
        // Trigger atomic DNS cache flush
        log::warn!("Handover Action: Executing atomic DNS cache flush to invalidate obsolete routes.");
        crate::dns::cache::GLOBAL_DNS_CACHE.clear();

        // Trigger immediate predictive prefetching/re-ping task to establish fastest route to closest tower gateway
        log::warn!("Handover Action: Triggering immediate predictive prefetch/re-ping cycle on global runtime.");
        trigger_handover_re_ping();
    }
}

/// Spawns the predictive DNS prefetching / re-ping cycle on the active Tokio runtime.
fn trigger_handover_re_ping() {
    // Retrieve the active configuration parameters from the global engine state
    let (primary, secondary, proto) = {
        let engine = match crate::ENGINE.lock() {
            Ok(guard) => guard,
            Err(_) => {
                log::error!("Handover Action: Failed to lock ENGINE state mutex.");
                return;
            }
        };
        if engine.primary_dns.is_empty() {
            log::warn!("Handover Action: DNS servers not configured in EngineState yet.");
            return;
        }
        (engine.primary_dns.clone(), engine.secondary_dns.clone(), engine.protocol.clone())
    };

    // Use our custom helper to spawn onto the global Tokio runtime thread-safely
    crate::spawn_on_global_runtime(async move {
        log::info!("Handover Action: Launching immediate gaming endpoints warm-up cycle...");
        // Re-ping matchmaking and gaming endpoints
        crate::dns::prefetch::trigger_immediate_prefetch_cycle(&primary, &secondary, &proto).await;
    });
}
