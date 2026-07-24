use std::os::unix::io::RawFd;

/// Sets socket options for QoS priority tagging (IP_TOS), kernel priority (SO_PRIORITY),
/// port reuse (SO_REUSEPORT), and 4MB socket buffer expansion (SO_RCVBUF, SO_SNDBUF).
/// This function is safe and wraps low-level `setsockopt` calls from `libc`.
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

    // 2. Set SO_PRIORITY to 6 (Interactive/Realtime Traffic class in Linux Kernel)
    let priority_val: libc::c_int = 6;
    unsafe {
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_PRIORITY,
            &priority_val as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        );
    }

    // 3. Enable SO_REUSEPORT for multi-threaded socket dispatching
    let reuse_val: libc::c_int = 1;
    unsafe {
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_REUSEPORT,
            &reuse_val as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        );
    }

    // 4. Expand Socket Buffer sizes to 4MB (4,194,304 bytes) to eliminate OS-level buffer overflow discards.
    let buffer_size: libc::c_int = 4_194_304;

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

    // 5. Enable O_NONBLOCK on socket
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL, 0) };
    if flags >= 0 {
        unsafe {
            libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
        }
    }

    log::info!("AetherUDP Socket Tuning applied successfully on RawFd={}! IP_TOS=0xB8, SO_PRIORITY=6, SO_RCVBUF/SO_SNDBUF=4MB, O_NONBLOCK.", fd);
    Ok(())
}


