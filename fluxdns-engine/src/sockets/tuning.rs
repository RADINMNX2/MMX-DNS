use std::os::unix::io::RawFd;
use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::jint;

/// Sets socket options for QoS priority tagging (IP_TOS) and socket buffer expansion (SO_RCVBUF, SO_SNDBUF).
/// This function is safe and wraps the low-level `setsockopt` calls from `libc`.
pub fn tune_socket_fd(fd: RawFd) -> Result<(), std::io::Error> {
    log::debug!("Applying native AetherUDP socket tuning on RawFd={}", fd);

    // 1. Configure QoS / DSCP Expedited Forwarding (IP_TOS)
    // DSCP EF (0x2E) shifted left by 2 is 0xB8.
    // This value instructs upstream switches and cellular base stations to prioritize these real-time UDP gaming packets.
    let ip_tos_val: libc::c_int = 0xB8;
    let res_tos = unsafe {
        libc::setsockopt(
            fd,
            libc::IPPROTO_IP,
            libc::IP_TOS,
            &ip_tos_val as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        )
    };

    if res_tos < 0 {
        let err = std::io::Error::last_os_error();
        log::error!("AetherUDP Tuning Error: Failed to set IP_TOS on fd {}: {:?}", fd, err);
        return Err(err);
    }

    // 2. Expand Socket Buffer sizes to 1MB (1,048,576 bytes) to eliminate OS-level buffer overflow discards.
    let buffer_size: libc::c_int = 1_048_576;

    // Expand Receive Buffer
    let res_rcv = unsafe {
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_RCVBUF,
            &buffer_size as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        )
    };

    if res_rcv < 0 {
        let err = std::io::Error::last_os_error();
        log::error!("AetherUDP Tuning Error: Failed to set SO_RCVBUF on fd {}: {:?}", fd, err);
        return Err(err);
    }

    // Expand Send Buffer
    let res_snd = unsafe {
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_SNDBUF,
            &buffer_size as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        )
    };

    if res_snd < 0 {
        let err = std::io::Error::last_os_error();
        log::error!("AetherUDP Tuning Error: Failed to set SO_SNDBUF on fd {}: {:?}", fd, err);
        return Err(err);
    }

    log::info!("AetherUDP Socket Tuning applied successfully on RawFd={}! IP_TOS=0xB8, SO_RCVBUF/SO_SNDBUF=1MB.", fd);
    Ok(())
}

/// JNI binding to allow the Android client to tune arbitrary file descriptors directly.
/// Maps OS-level error numbers (`errno`) to structured negative integers for high-fidelity Kotlin diagnostics.
#[no_mangle]
pub extern "system" fn Java_com_example_service_FluxDnsEngine_tuneSocketNative(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
) -> jint {
    match tune_socket_fd(fd as RawFd) {
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
