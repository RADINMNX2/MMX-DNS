use std::net::{SocketAddr, UdpSocket};
use std::time::{Duration, Instant};
use crate::protocols::session_cache::GLOBAL_SESSION_CACHE;

/// Custom error type for DoH3 client operations
#[derive(Debug)]
pub enum Doh3Error {
    Io(std::io::Error),
    Quiche(quiche::Error),
    H3(quiche::h3::Error),
    Timeout,
    HandshakeFailed,
    StreamError,
    EngineError(String),
}

impl std::fmt::Display for Doh3Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Doh3Error::Io(e) => write!(f, "IO Error: {}", e),
            Doh3Error::Quiche(e) => write!(f, "Quiche Error: {}", e),
            Doh3Error::H3(e) => write!(f, "HTTP/3 Error: {}", e),
            Doh3Error::Timeout => write!(f, "Operation timed out"),
            Doh3Error::HandshakeFailed => write!(f, "QUIC/H3 handshake failed"),
            Doh3Error::StreamError => write!(f, "HTTP/3 stream execution failed"),
            Doh3Error::EngineError(s) => write!(f, "Engine Error: {}", s),
        }
    }
}

impl std::error::Error for Doh3Error {}

impl From<std::io::Error> for Doh3Error {
    fn from(err: std::io::Error) -> Self {
        Doh3Error::Io(err)
    }
}

impl From<quiche::Error> for Doh3Error {
    fn from(err: quiche::Error) -> Self {
        Doh3Error::Quiche(err)
    }
}

impl From<quiche::h3::Error> for Doh3Error {
    fn from(err: quiche::h3::Error) -> Self {
        Doh3Error::H3(err)
    }
}

/// Resolves a DNS query using DNS-over-HTTP/3 (DoH3) over Port 443.
/// This client supports 0-RTT handshakes and fallback functionality.
pub async fn resolve_via_doh3(
    query: &[u8],
    dns_ip: &str,
    port: u16,
) -> Result<Vec<u8>, Doh3Error> {
    log::info!("Initiating DoH3 (HTTP/3) resolution for server {}:{}", dns_ip, port);

    let peer_addr: SocketAddr = format!("{}:{}", dns_ip, port)
        .parse()
        .map_err(|e| Doh3Error::EngineError(format!("Invalid DNS IP address: {}", e)))?;
        
    let bind_addr: SocketAddr = if peer_addr.is_ipv6() {
        "[::]:0".parse().unwrap()
    } else {
        "0.0.0.0:0".parse().unwrap()
    };

    let socket = UdpSocket::bind(bind_addr)?;
    socket.set_nonblocking(true)?;

    // Create quiche QUIC configuration
    let mut config = quiche::Config::new(quiche::PROTOCOL_VERSION)?;
    // h3 ALPN configuration
    config.set_application_protos(&[b"h3"])?;
    config.set_max_idle_timeout(5000);
    config.set_max_recv_udp_payload_size(1350);
    config.set_max_send_udp_payload_size(1350);
    config.set_initial_max_data(10_000_000);
    config.set_initial_max_stream_data_bidi_local(1_000_000);
    config.set_initial_max_stream_data_bidi_remote(1_000_000);
    config.set_initial_max_streams_bidi(100);
    config.set_initial_max_stream_data_uni(1_000_000);
    config.set_migration(true);

    // Enable 0-RTT
    config.enable_early_data();

    // Generate secure random Source Connection ID
    let mut scid = [0u8; quiche::MAX_CONN_ID_LEN];
    let ring_rc = ring::rand::SystemRandom::new();
    if let Err(_) = ring::rand::SecureRandom::fill(&ring_rc, &mut scid) {
        return Err(Doh3Error::EngineError("Failed to generate secure connection ID".to_string()));
    }
    let scid = quiche::ConnectionId::from_ref(&scid);

    let server_name = "dns.google"; // TLS SNI hostname
    let local_addr = socket.local_addr()?;
    let mut conn = quiche::connect(Some(server_name), &scid, local_addr, peer_addr, &mut config)?;

    // Retrieve cached session ticket for 0-RTT resumption
    let cache_key = format!("h3:{}:{}", dns_ip, port);
    let mut early_data_active = false;
    if let Some(ticket) = GLOBAL_SESSION_CACHE.get(&cache_key) {
        log::info!("DoH3 Client: Applying cached session ticket to attempt 0-RTT resumption.");
        if let Err(e) = conn.set_session_ticket(&ticket) {
            log::warn!("DoH3: Failed to apply session ticket: {:?}", e);
        } else {
            early_data_active = true;
        }
    }

    let mut buf = [0u8; 65535];
    let mut write_buf = [0u8; 65535];

    let start_time = Instant::now();
    let timeout_duration = Duration::from_millis(3000);
    
    // HTTP/3 connection variables
    let mut h3_conn: Option<quiche::h3::Connection> = None;
    let mut h3_config = quiche::h3::Config::new()?;
    let mut request_sent = false;
    let mut request_stream_id: Option<u64> = None;
    let mut response_payload: Option<Vec<u8>> = None;

    // Duplex event loop
    loop {
        if start_time.elapsed() > timeout_duration {
            return Err(Doh3Error::Timeout);
        }

        // 1. Flush outbound QUIC packets
        loop {
            match conn.send(&mut write_buf) {
                Ok((bytes_written, send_info)) => {
                    if let Err(e) = socket.send_to(&write_buf[..bytes_written], send_info.to) {
                        if e.kind() == std::io::ErrorKind::WouldBlock {
                            break;
                        }
                        return Err(Doh3Error::Io(e));
                    }
                }
                Err(quiche::Error::Done) => {
                    break;
                }
                Err(e) => {
                    return Err(Doh3Error::Quiche(e));
                }
            }
        }

        // 2. Consume inbound UDP packets
        loop {
            match socket.recv_from(&mut buf) {
                Ok((bytes_read, from_addr)) => {
                    let recv_info = quiche::RecvInfo {
                        from: from_addr,
                        to: socket.local_addr().unwrap(),
                    };
                    if let Err(e) = conn.recv(&mut buf[..bytes_read], recv_info) {
                        log::error!("DoH3: Error receiving packet: {:?}", e);
                        continue;
                    }
                }
                Err(e) => {
                    if e.kind() == std::io::ErrorKind::WouldBlock {
                        break;
                    }
                    return Err(Doh3Error::Io(e));
                }
            }
        }

        // 3. Initialize HTTP/3 Connection once QUIC handshakes or 0-RTT is active
        if h3_conn.is_none() && (conn.is_established() || (early_data_active && conn.is_in_early_data())) {
            log::info!("DoH3: Connection ready. Binding HTTP/3 protocol handler.");
            match quiche::h3::Connection::with_transport(&mut conn, &mut h3_config) {
                Ok(h3) => h3_conn = Some(h3),
                Err(e) => {
                    log::error!("DoH3: Failed to establish HTTP/3 transport connection: {:?}", e);
                    return Err(Doh3Error::H3(e));
                }
            }
        }

        // 4. Send HTTP/3 POST Request
        if let Some(ref mut h3) = h3_conn {
            if !request_sent {
                // HTTP/3 POST Headers
                let headers = vec![
                    quiche::h3::Header::new(b":method", b"POST"),
                    quiche::h3::Header::new(b":scheme", b"https"),
                    quiche::h3::Header::new(b":authority", dns_ip.as_bytes()),
                    quiche::h3::Header::new(b":path", b"/dns-query"),
                    quiche::h3::Header::new(b"content-type", b"application/dns-message"),
                    quiche::h3::Header::new(b"accept", b"application/dns-message"),
                    quiche::h3::Header::new(b"content-length", &query.len().to_string().into_bytes()),
                ];

                log::info!("DoH3: Dispatched HTTP/3 POST headers.");
                match h3.send_request(&mut conn, &headers, false) {
                    Ok(stream_id) => {
                        log::info!("DoH3: HTTP/3 stream established with ID: {}", stream_id);
                        request_stream_id = Some(stream_id);
                        
                        // Write POST payload (the raw DNS query bytes) and set FIN to close half-connection
                        match h3.send_body(&mut conn, stream_id, query, true) {
                            Ok(_) => {
                                request_sent = true;
                                log::info!("DoH3: DNS request body successfully dispatched.");
                            }
                            Err(e) => {
                                log::error!("DoH3: Failed to write request body: {:?}", e);
                                return Err(Doh3Error::H3(e));
                            }
                        }
                    }
                    Err(e) => {
                        log::error!("DoH3: Failed to dispatch HTTP/3 request headers: {:?}", e);
                        return Err(Doh3Error::H3(e));
                    }
                }
            }
        }

        // 5. Poll and process HTTP/3 Events
        if let Some(ref mut h3) = h3_conn {
            if let Some(stream_id) = request_stream_id {
                loop {
                    match h3.poll(&mut conn) {
                        Ok((id, quiche::h3::Event::Headers { list, .. })) => {
                            if id == stream_id {
                                log::info!("DoH3: Received response headers for stream {}", id);
                                for h in list {
                                    let name = String::from_utf8_lossy(h.name());
                                    let value = String::from_utf8_lossy(h.value());
                                    log::info!("DoH3 Header -> {}: {}", name, value);
                                }
                            }
                        }
                        Ok((id, quiche::h3::Event::Data)) => {
                            if id == stream_id {
                                let mut chunk = [0u8; 8192];
                                match h3.recv_body(&mut conn, id, &mut chunk) {
                                    Ok(bytes_read) => {
                                        log::info!("DoH3: Received response body chunk of {} bytes", bytes_read);
                                        let payload = chunk[..bytes_read].to_vec();
                                        if response_payload.is_none() {
                                            response_payload = Some(payload);
                                        } else {
                                            response_payload.as_mut().unwrap().extend_from_slice(&payload);
                                        }
                                    }
                                    Err(quiche::h3::Error::Done) => {
                                        break;
                                    }
                                    Err(e) => {
                                        log::error!("DoH3 error reading response body: {:?}", e);
                                        return Err(Doh3Error::H3(e));
                                    }
                                }
                            }
                        }
                        Ok((id, quiche::h3::Event::Finished)) => {
                            if id == stream_id {
                                log::info!("DoH3: HTTP/3 stream completed query transaction successfully.");
                                
                                // Cache Session Ticket for the next 0-RTT handshake
                                if let Some(ticket) = conn.session_ticket() {
                                    GLOBAL_SESSION_CACHE.insert(&cache_key, ticket);
                                }
                                
                                break;
                            }
                        }
                        Ok(_) => {
                            // Suppress unrelated connection control events
                        }
                        Err(quiche::h3::Error::Done) => {
                            break;
                        }
                        Err(e) => {
                            log::error!("DoH3 poll error: {:?}", e);
                            return Err(Doh3Error::H3(e));
                        }
                    }
                }
                
                // Terminate loop once response is fully acquired
                if response_payload.is_some() && conn.is_closed() {
                    break;
                }
                
                // If stream was completed, exit gracefully
                if response_payload.is_some() {
                    // Force shut the QUIC connection to clear resources
                    let _ = conn.close(true, 0x00, b"Success");
                    break;
                }
            }
        }

        if conn.is_closed() {
            log::info!("DoH3: QUIC connection closed.");
            break;
        }

        std::thread::sleep(Duration::from_millis(10));
    }

    match response_payload {
        Some(payload) => Ok(payload),
        None => Err(Doh3Error::EngineError("No HTTP/3 response payload returned".to_string())),
    }
}
