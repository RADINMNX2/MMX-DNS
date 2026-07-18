use std::net::SocketAddr;
use std::os::unix::io::AsRawFd;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::SystemTime;
use tokio::net::UdpSocket;
use crate::sockets::racing::bind_socket_to_cellular;

// Global atomic holding the last packet timestamp in milliseconds since epoch
pub static LAST_PACKET_TIME: AtomicU64 = AtomicU64::new(0);

/**
 * Updates the recorded last packet timestamp with the current epoch milliseconds.
 * This is invoked by the main TUN reading and writing loops to reset the idle timer.
 */
pub fn update_activity() {
    if let Ok(duration) = SystemTime::now().duration_since(std::time::UNIX_EPOCH) {
        LAST_PACKET_TIME.store(duration.as_millis() as u64, Ordering::SeqCst);
    }
}

/**
 * Starts the background thread daemon to monitor the idle state of the raw TUN packet forwarding loop.
 * If no packet activity is detected for 4.5 seconds, it generates and injects a 1-byte raw UDP micro-probe.
 *
 * @param anycast_ip The highly available DNS server or Anycast IP to target with the micro-probe.
 */
pub fn start_daemon(anycast_ip: String) {
    tokio::spawn(async move {
        log::info!("RRC Connection Pinning Daemon: Started with Anycast IP: {}", anycast_ip);
        // Initialize activity to the present time so we don't trigger immediately on launch
        update_activity();

        loop {
            // Check current status every 500 milliseconds
            tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;

            let last_activity = LAST_PACKET_TIME.load(Ordering::SeqCst);
            let current_time = match SystemTime::now().duration_since(std::time::UNIX_EPOCH) {
                Ok(dur) => dur.as_millis() as u64,
                Err(_) => continue,
            };

            let elapsed_ms = current_time.saturating_sub(last_activity);
            if elapsed_ms >= 4500 {
                log::info!("RRC Connection Pinning Daemon: Idle duration reached {} ms (threshold 4500 ms). Injecting cellular micro-probe to keep RRC_CONNECTED.", elapsed_ms);
                
                // Instantly refresh the last packet time to prevent double-firing in subsequent loops
                update_activity();

                // Fire the micro-probe packet
                inject_micro_probe(&anycast_ip).await;
            }
        }
    });
}

/**
 * Generates and injects a 1-byte raw UDP micro-probe packet directly to the cellular network interface.
 * Bypasses the active VPN TUN routing loop by binding and routing the underlying raw socket to cell.
 */
async fn inject_micro_probe(anycast_ip: &str) {
    let dest_addr: SocketAddr = match format!("{}:53", anycast_ip).parse() {
        Ok(addr) => addr,
        Err(e) => {
            log::error!("RRC Connection Pinning Daemon: Failed to parse Anycast IP address '{}': {:?}", anycast_ip, e);
            return;
        }
    };

    // Instantiate standard library UDP socket
    let std_sock = match std::net::UdpSocket::bind("0.0.0.0:0") {
        Ok(sock) => sock,
        Err(e) => {
            log::error!("RRC Connection Pinning Daemon: Failed to bind standard UDP socket: {:?}", e);
            return;
        }
    };

    let fd = std_sock.as_raw_fd();

    // Route the socket exclusively through the physical cellular network interface (bypasses VPN routing)
    if !bind_socket_to_cellular(fd) {
        log::warn!("RRC Connection Pinning Daemon: JNI bind_socket_to_cellular failed for socket FD {}. Attempting default routing fallback.", fd);
    } else {
        log::debug!("RRC Connection Pinning Daemon: Successfully bound socket FD {} to cellular interface.", fd);
    }

    if let Err(e) = std_sock.set_nonblocking(true) {
        log::error!("RRC Connection Pinning Daemon: Failed to set non-blocking state: {:?}", e);
        return;
    }

    // Convert standard socket to Tokio UdpSocket
    let tokio_sock = match UdpSocket::from_std(std_sock) {
        Ok(sock) => sock,
        Err(e) => {
            log::error!("RRC Connection Pinning Daemon: Failed to convert std socket to tokio: {:?}", e);
            return;
        }
    };

    // 1-byte lightweight UDP micro-probe payload (resets carrier dormancy timer)
    let probe_payload = [0x00u8];
    match tokio_sock.send_to(&probe_payload, dest_addr).await {
        Ok(bytes_sent) => {
            log::info!("RRC Connection Pinning Daemon: Successfully injected {} bytes of micro-probe to {}", bytes_sent, dest_addr);
        }
        Err(e) => {
            log::error!("RRC Connection Pinning Daemon: Failed to send micro-probe to {}: {:?}", dest_addr, e);
        }
    }
}
