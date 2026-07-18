use std::os::unix::io::RawFd;
use std::sync::atomic::{AtomicU32, Ordering};
use std::net::SocketAddr;
use std::io::{Error, ErrorKind};

// Linux-specific IP socket option constants
const IP_MTU_DISCOVER: libc::c_int = 10;
const IP_PMTUDISC_DO: libc::c_int = 2; // Always set DF (Don't Fragment) flag
const IP_MTU: libc::c_int = 14;       // Retrieve current discovered PMTU

/// Tracks and manages the Path MTU (PMTU) dynamically.
pub struct MtuManager {
    current_mtu: AtomicU32,
}

impl MtuManager {
    /// Creates a new `MtuManager` with a standard Ethernet MTU default (1500 bytes).
    pub fn new() -> Self {
        MtuManager {
            current_mtu: AtomicU32::new(1500),
        }
    }

    /// Gets the currently tracked PMTU.
    pub fn get_mtu(&self) -> u32 {
        self.current_mtu.load(Ordering::Relaxed)
    }

    /// Safely updates/clamps the tracked MTU to a smaller value if needed.
    pub fn update_mtu(&self, mut new_mtu: u32) {
        // Enforce safe limits: never go below standard IPv6 minimum MTU (1280) 
        // unless absolutely forced (IPv4 minimum is 576).
        if new_mtu < 576 {
            new_mtu = 576;
        }
        
        let mut prev = self.current_mtu.load(Ordering::Relaxed);
        while new_mtu < prev {
            match self.current_mtu.compare_exchange_weak(
                prev,
                new_mtu,
                Ordering::SeqCst,
                Ordering::SeqCst,
            ) {
                Ok(_) => {
                    log::info!("AetherUDP PMTU Clamping: Tracked MTU clamped from {} to {}", prev, new_mtu);
                    break;
                }
                Err(actual) => prev = actual,
            }
        }
    }

    /// Queries the Linux kernel's routing cache to retrieve the actual PMTU discovered for the socket.
    pub fn query_kernel_pmtu(&self, fd: RawFd) -> Option<u32> {
        let mut mtu_val: libc::c_int = 0;
        let mut len = std::mem::size_of::<libc::c_int>() as libc::socklen_t;

        let res = unsafe {
            libc::getsockopt(
                fd,
                libc::IPPROTO_IP,
                IP_MTU,
                &mut mtu_val as *mut _ as *mut libc::c_void,
                &mut len,
            )
        };

        if res == 0 && mtu_val > 0 {
            log::debug!("Successfully queried kernel PMTU: {} bytes", mtu_val);
            Some(mtu_val as u32)
        } else {
            let err = std::io::Error::last_os_error();
            log::warn!("Failed to query kernel PMTU via getsockopt: {:?}", err);
            None
        }
    }
}

lazy_static::lazy_static! {
    /// Global PMTU manager for general UDP streams.
    pub static ref GLOBAL_MTU_MANAGER: MtuManager = MtuManager::new();
}

/// Sets the socket options to prevent packet fragmentation by enforcing the IP Don't Fragment (DF) flag.
pub fn enforce_df_flag(fd: RawFd) -> Result<(), std::io::Error> {
    log::debug!("Enforcing DF (Don't Fragment) flag on RawFd={}", fd);

    let val: libc::c_int = IP_PMTUDISC_DO;
    let res = unsafe {
        libc::setsockopt(
            fd,
            libc::IPPROTO_IP,
            IP_MTU_DISCOVER,
            &val as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        )
    };

    if res < 0 {
        let err = std::io::Error::last_os_error();
        log::error!("AetherUDP MTU Error: Failed to set IP_MTU_DISCOVER on fd {}: {:?}", fd, err);
        return Err(err);
    }

    log::info!("Successfully enforced DF flag on RawFd={} using IP_MTU_DISCOVER.", fd);
    Ok(())
}

/// Asynchronous UDP writer loop with built-in EMSGSIZE interceptors and dynamic PMTU recovery.
pub async fn send_packet_clamped(
    socket: &tokio::net::UdpSocket,
    payload: &[u8],
    target: SocketAddr,
) -> std::io::Result<usize> {
    use std::os::unix::io::AsRawFd;
    let fd = socket.as_raw_fd();

    // Ensure socket has DF flag configured
    let _ = enforce_df_flag(fd);

    let current_limit = GLOBAL_MTU_MANAGER.get_mtu();
    
    // If the payload exceeds our tracked MTU, we can log a warning or clamp/notify upstream.
    // Standard UDP doesn't support application-agnostic segment slicing easily, but we'll adaptively clamp.
    let payload_to_send = if payload.len() > current_limit as usize {
        log::warn!(
            "Payload size ({} bytes) exceeds current PMTU clamp ({} bytes). Outgoing stream will be truncated/clamped.",
            payload.len(),
            current_limit
        );
        &payload[..current_limit as usize]
    } else {
        payload
    };

    match socket.send_to(payload_to_send, target).await {
        Ok(bytes_sent) => Ok(bytes_sent),
        Err(ref e) if e.kind() == ErrorKind::MessageTooLarge => {
            log::warn!("EMSGSIZE (Message too large) caught during transmit to {}. Initiating PMTU discovery...", target);

            // 1. Try to query the precise PMTU from the Linux kernel
            if let Some(kernel_pmtu) = GLOBAL_MTU_MANAGER.query_kernel_pmtu(fd) {
                GLOBAL_MTU_MANAGER.update_mtu(kernel_pmtu);
            } else {
                // 2. Fallback: Decrement the current MTU clamp by a step of 64 bytes
                let next_clamp = current_limit.saturating_sub(64);
                GLOBAL_MTU_MANAGER.update_mtu(next_clamp);
            }

            // Retry transmit with the new clamped limits
            let updated_limit = GLOBAL_MTU_MANAGER.get_mtu() as usize;
            let retry_payload = if payload.len() > updated_limit {
                &payload[..updated_limit]
            } else {
                payload
            };

            log::info!("Retrying transmission to {} with clamped payload size ({} bytes)...", target, retry_payload.len());
            socket.send_to(retry_payload, target).await
        }
        Err(e) => Err(e),
    }
}

/// JNI binding to allow the Android client to enforce the Don't Fragment flag on a raw file descriptor.
#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_enforceDfNative(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    fd: jni::sys::jint,
) -> jni::sys::jint {
    match enforce_df_flag(fd as RawFd) {
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

/// JNI binding to retrieve the globally tracked/clamped Path MTU size.
#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_getTrackedMtuNative(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jint {
    GLOBAL_MTU_MANAGER.get_mtu() as jni::sys::jint
}

/// JNI binding to manually set or override the tracked/clamped Path MTU size.
#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_setTrackedMtuNative(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    mtu: jni::sys::jint,
) {
    GLOBAL_MTU_MANAGER.update_mtu(mtu as u32);
}

/// JNI binding to query the actual PMTU from the kernel's routing cache on demand.
#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_queryKernelMtuNative(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
    fd: jni::sys::jint,
) -> jni::sys::jint {
    match GLOBAL_MTU_MANAGER.query_kernel_pmtu(fd as RawFd) {
        Some(mtu) => mtu as jni::sys::jint,
        None => -1,
    }
}

