use std::net::{SocketAddr, UdpSocket as StdUdpSocket};
use std::sync::{Mutex, Arc};
use std::collections::{VecDeque, HashMap};
use std::os::unix::io::{AsRawFd, RawFd};
use tokio::net::UdpSocket;
use tokio::sync::oneshot;
use tokio::time::Duration;
use jni::sys::jint;
use once_cell::sync::Lazy;

// Reference to global JVM initialized in lib.rs
extern "C" {
    pub static mut JVM: Option<jni::JavaVM>;
}

/// Helper function to invoke JVM static method on FluxDnsEngine to bind a socket to Wi-Fi.
pub fn bind_socket_to_wifi(fd: RawFd) -> bool {
    let jvm = unsafe {
        match &JVM {
            Some(vm) => vm,
            None => {
                log::error!("AetherUDP Racing: JVM reference not initialized in JNI_OnLoad.");
                return false;
            }
        }
    };

    let mut env = match jvm.attach_current_thread() {
        Ok(env) => env,
        Err(e) => {
            log::error!("AetherUDP Racing: Failed to attach thread to JVM: {:?}", e);
            return false;
        }
    };

    let cls = match env.find_class("com/example/service/FluxDnsEngine") {
        Ok(c) => c,
        Err(e) => {
            log::error!("AetherUDP Racing: Failed to find FluxDnsEngine class: {:?}", e);
            return false;
        }
    };

    let res = env.call_static_method(
        &cls,
        "bindSocketToWifi",
        "(I)Z",
        &[jni::objects::JValue::Int(fd as jint)],
    );

    match res {
        Ok(val) => val.z().unwrap_or(false),
        Err(e) => {
            log::error!("AetherUDP Racing: bindSocketToWifi JNI call failed: {:?}", e);
            false
        }
    }
}

/// Helper function to invoke JVM static method on FluxDnsEngine to bind a socket to Cellular.
pub fn bind_socket_to_cellular(fd: RawFd) -> bool {
    let jvm = unsafe {
        match &JVM {
            Some(vm) => vm,
            None => {
                log::error!("AetherUDP Racing: JVM reference not initialized in JNI_OnLoad.");
                return false;
            }
        }
    };

    let mut env = match jvm.attach_current_thread() {
        Ok(env) => env,
        Err(e) => {
            log::error!("AetherUDP Racing: Failed to attach thread to JVM: {:?}", e);
            return false;
        }
    };

    let cls = match env.find_class("com/example/service/FluxDnsEngine") {
        Ok(c) => c,
        Err(e) => {
            log::error!("AetherUDP Racing: Failed to find FluxDnsEngine class: {:?}", e);
            return false;
        }
    };

    let res = env.call_static_method(
        &cls,
        "bindSocketToCellular",
        "(I)Z",
        &[jni::objects::JValue::Int(fd as jint)],
    );

    match res {
        Ok(val) => val.z().unwrap_or(false),
        Err(e) => {
            log::error!("AetherUDP Racing: bindSocketToCellular JNI call failed: {:?}", e);
            false
        }
    }
}

/// AetherUDP Multi-Path Racing Coordinator.
/// Manages dual network sockets (Wi-Fi + Mobile Cellular) to dispatch duplicated packets
/// in parallel, performing sub-millisecond deduplication on the receiver path.
pub struct RacingCoordinator {
    pub wifi_socket: Option<Arc<UdpSocket>>,
    pub cellular_socket: Option<Arc<UdpSocket>>,
    // High-performance deduplication rolling history
    seen_transactions: Mutex<VecDeque<u16>>,
    max_history_size: usize,
    // Multiplexing registry for pending DNS transactions
    pending_queries: Arc<Mutex<HashMap<u16, oneshot::Sender<Vec<u8>>>>>,
}

impl RacingCoordinator {
    /// Creates and binds dual multi-path UDP sockets, linking them to Wi-Fi and Cellular respectively.
    pub fn new() -> Self {
        log::info!("Initializing AetherUDP Multi-Path Racing Sockets...");

        // 1. Create and bind Wi-Fi bound socket
        let wifi_sock = match StdUdpSocket::bind("0.0.0.0:0") {
            Ok(sock) => {
                let fd = sock.as_raw_fd();
                if bind_socket_to_wifi(fd) {
                    log::info!("AetherUDP: Wifi Socket successfully bound to Wi-Fi interface (FD={}).", fd);
                    sock.set_nonblocking(true).ok();
                    UdpSocket::from_std(sock).ok().map(Arc::new)
                } else {
                    log::warn!("AetherUDP: Wi-Fi bind request failed. Wi-Fi path fallback disabled.");
                    None
                }
            }
            Err(e) => {
                log::error!("AetherUDP: Failed to bind Wi-Fi socket: {:?}", e);
                None
            }
        };

        // 2. Create and bind Cellular bound socket
        let cellular_sock = match StdUdpSocket::bind("0.0.0.0:0") {
            Ok(sock) => {
                let fd = sock.as_raw_fd();
                if bind_socket_to_cellular(fd) {
                    log::info!("AetherUDP: Cellular Socket successfully bound to Mobile Cellular interface (FD={}).", fd);
                    sock.set_nonblocking(true).ok();
                    UdpSocket::from_std(sock).ok().map(Arc::new)
                } else {
                    log::warn!("AetherUDP: Cellular bind request failed. Cellular path fallback disabled.");
                    None
                }
            }
            Err(e) => {
                log::error!("AetherUDP: Failed to bind Cellular socket: {:?}", e);
                None
            }
        };

        let pending_queries = Arc::new(Mutex::new(HashMap::new()));

        // Start asynchronous listen loops on bound sockets if available
        if let Some(ref wifi) = wifi_sock {
            Self::start_listen_loop(wifi.clone(), pending_queries.clone(), "Wi-Fi");
        }
        if let Some(ref cellular) = cellular_sock {
            Self::start_listen_loop(cellular.clone(), pending_queries.clone(), "Cellular");
        }

        RacingCoordinator {
            wifi_socket: wifi_sock,
            cellular_socket: cellular_sock,
            seen_transactions: Mutex::new(VecDeque::with_capacity(1024)),
            max_history_size: 1000,
            pending_queries,
        }
    }

    /// Spawns a background listener for a socket, routing received payloads directly to waiting oneshot channels.
    fn start_listen_loop(
        socket: Arc<UdpSocket>,
        pending: Arc<Mutex<HashMap<u16, oneshot::Sender<Vec<u8>>>>>,
        tag: &'static str,
    ) {
        tokio::spawn(async move {
            let mut buf = [0u8; 4096];
            log::info!("AetherUDP Racing: Listen loop started for {} path.", tag);
            loop {
                match socket.recv_from(&mut buf).await {
                    Ok((len, addr)) => {
                        let pkt = &buf[..len];
                        if len >= 2 {
                            let tx_id = u16::from_be_bytes([pkt[0], pkt[1]]);
                            let mut map = pending.lock().unwrap();
                            if let Some(tx_chan) = map.remove(&tx_id) {
                                log::debug!(
                                    "AetherUDP Racing: Response received on {} from {}. Delivering to client.",
                                    tag,
                                    addr
                                );
                                let _ = tx_chan.send(pkt.to_vec());
                            }
                        }
                    }
                    Err(e) => {
                        log::error!("AetherUDP Racing: {} listen loop encountered read error: {:?}", tag, e);
                        break;
                    }
                }
            }
        });
    }

    /// Checks if any of the multi-path interfaces have successfully bound active sockets.
    pub fn has_active_sockets(&self) -> bool {
        self.wifi_socket.is_some() || self.cellular_socket.is_some()
    }

    /// Performs multiplexed multi-path DNS resolution by racing queries across both paths simultaneously.
    pub async fn resolve_dns(&self, query: &[u8], dns_ip: &str) -> Option<Vec<u8>> {
        if !self.has_active_sockets() {
            return None;
        }

        if query.len() < 2 {
            return None;
        }

        let tx_id = u16::from_be_bytes([query[0], query[1]]);
        let (tx, rx) = oneshot::channel();

        // Register the pending transaction ID
        {
            let mut pending = self.pending_queries.lock().unwrap();
            pending.insert(tx_id, tx);
        }

        let target_addr: SocketAddr = format!("{}:53", dns_ip).parse().ok()?;

        // Send to both sockets in parallel
        let mut sent = false;
        if let Some(ref wifi) = self.wifi_socket {
            if wifi.send_to(query, target_addr).await.is_ok() {
                sent = true;
            }
        }
        if let Some(ref cellular) = self.cellular_socket {
            if cellular.send_to(query, target_addr).await.is_ok() {
                sent = true;
            }
        }

        if !sent {
            // Clean up registry if send failed completely
            let mut pending = self.pending_queries.lock().unwrap();
            pending.remove(&tx_id);
            return None;
        }

        // Wait for first response with a low-latency 1.5-second timeout limit
        match tokio::time::timeout(Duration::from_millis(1500), rx).await {
            Ok(Ok(res)) => {
                // Register transaction in deduplication list
                self.deduplicate_packet(tx_id);
                Some(res)
            }
            _ => {
                // Timeout or canceled, cleanup registry entry
                let mut pending = self.pending_queries.lock().unwrap();
                pending.remove(&tx_id);
                None
            }
        }
    }

    /// Evaluates if an incoming packet's sequence / transaction ID has already been delivered,
    /// filtering out any slow redundant duplicates from the racing paths.
    pub fn deduplicate_packet(&self, transaction_id: u16) -> bool {
        let mut history = self.seen_transactions.lock().unwrap();
        
        if history.contains(&transaction_id) {
            log::debug!("AetherUDP Multi-Path: Duplicate transaction ID {} caught and discarded.", transaction_id);
            true // Duplicate, ignore!
        } else {
            // Register new unique transaction ID in sliding history window
            if history.len() >= self.max_history_size {
                history.pop_front();
            }
            history.push_back(transaction_id);
            false // Unique packet, process!
        }
    }
}

pub static RACING_COORDINATOR: Lazy<RacingCoordinator> = Lazy::new(|| {
    RacingCoordinator::new()
});
