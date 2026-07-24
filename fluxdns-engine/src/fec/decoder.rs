use std::collections::{HashMap, VecDeque};
use std::sync::Mutex;
use crate::fec::{gf_add, gf_mul, invert_matrix, AetherFecHeader};

pub struct AetherFecDecoder {
    k: usize, // Number of data packets (e.g. 10)
    m: usize, // Number of parity packets (e.g. 2)
    generator_matrix: Vec<Vec<u8>>, // Full (K+M) x K matrix (Identity followed by Cauchy)
}

impl AetherFecDecoder {
    pub fn new(k: usize, m: usize) -> Self {
        assert!(k > 0 && m > 0 && k + m <= 256, "Invalid FEC block dimensions");

        // Construct full (K+M) x K generator matrix
        let mut generator_matrix = vec![vec![0u8; k]; k + m];
        
        // First K rows: Identity Matrix
        for r in 0..k {
            generator_matrix[r][r] = 1;
        }

        // Next M rows: Cauchy Matrix
        for i in 0..m {
            let x_i = (i + k) as u8;
            for j in 0..k {
                let y_j = j as u8;
                let den = gf_add(x_i, y_j);
                generator_matrix[k + i][j] = crate::fec::gf_div(1, den);
            }
        }

        AetherFecDecoder { k, m, generator_matrix }
    }

    /// Reconstructs the original K data packets given any set of at least K received packets.
    /// `received_packets` is a slice of tuples: (index_in_block, packet_bytes).
    pub fn decode_block(
        &self,
        received_packets: &[(u8, Vec<u8>)],
    ) -> Result<Vec<Vec<u8>>, &'static str> {
        if received_packets.len() < self.k {
            return Err("Not enough packets received to perform FEC reconstruction");
        }

        // Take exactly K packets for decoding
        let subset = &received_packets[..self.k];
        let packet_size = subset[0].1.len();

        // 1. Build the K x K reconstruction matrix from the generator matrix rows
        let mut rec_matrix = vec![vec![0u8; self.k]; self.k];
        for r in 0..self.k {
            let index_in_block = subset[r].0 as usize;
            if index_in_block >= self.k + self.m {
                return Err("Received packet index out of valid FEC bounds");
            }
            rec_matrix[r].copy_from_slice(&self.generator_matrix[index_in_block]);
        }

        // 2. Invert the reconstruction matrix using Gauss-Jordan elimination
        let inv_matrix = match invert_matrix(&mut rec_matrix) {
            Some(inv) => inv,
            None => return Err("Failed to invert FEC reconstruction matrix (singular matrix)"),
        };

        // 3. Multiply inverted matrix by received packets to reconstruct original K data packets
        let mut reconstructed = vec![vec![0u8; packet_size]; self.k];
        for i in 0..self.k {
            let inv_row = &inv_matrix[i];
            let target_packet = &mut reconstructed[i];

            for j in 0..self.k {
                let factor = inv_row[j];
                if factor == 0 {
                    continue;
                }
                let src_packet = &subset[j].1;
                if factor == 1 {
                    for b in 0..packet_size {
                        target_packet[b] ^= src_packet[b];
                    }
                } else {
                    for b in 0..packet_size {
                        target_packet[b] = gf_add(target_packet[b], gf_mul(src_packet[b], factor));
                    }
                }
            }
        }

        Ok(reconstructed)
    }
}

/// Dynamic, lock-free/thread-safe Jitter Stabilization Buffer
/// Keeps track of received FEC frames for multiple gaming sessions, automatically
/// reconstructing any lost packet in a block once K blocks are successfully received.
pub struct JitterStabilizationBuffer {
    // Maps block_id -> (Count of packets, list of (index, packet_data))
    blocks: Mutex<HashMap<u16, (usize, Vec<(u8, Vec<u8>)>)>>,
    // Reordered output queue ready to be delivered to the application layer
    delivery_queue: Mutex<VecDeque<Vec<u8>>>,
    max_history_blocks: usize,
    history_ids: Mutex<VecDeque<u16>>,
}

impl JitterStabilizationBuffer {
    pub fn new(_k: usize, _m: usize) -> Self {
        JitterStabilizationBuffer {
            blocks: Mutex::new(HashMap::new()),
            delivery_queue: Mutex::new(VecDeque::new()),
            max_history_blocks: 100,
            history_ids: Mutex::new(VecDeque::new()),
        }
    }

    /// Feeds an incoming raw UDP packet into the Jitter Buffer.
    /// If the packet is a structured FEC block, it processes it, triggers reconstruction
    /// on completion, and pushes ordered payloads into the delivery queue.
    pub fn insert_packet(&self, raw_data: Vec<u8>) -> Option<Vec<Vec<u8>>> {
        // Attempt to parse Aether FEC Header
        let header = match AetherFecHeader::deserialize(&raw_data) {
            Some(h) => h,
            None => {
                // Not an Aether FEC packet; pass it directly back as-is
                return Some(vec![raw_data]);
            }
        };

        let block_id = header.block_id;
        let pkt_index = header.packet_index;

        let mut blocks = self.blocks.lock().unwrap();
        let entry = blocks.entry(block_id).or_insert_with(|| (0, Vec::new()));
        
        // Guard against duplicate packet delivery
        if entry.1.iter().any(|(idx, _)| *idx == pkt_index) {
            return None;
        }

        // Add to block list
        entry.0 += 1;
        
        // Strip the 8-byte FEC header to isolate the inner transport envelope
        let inner_payload = raw_data[AetherFecHeader::SIZE..].to_vec();
        entry.1.push((pkt_index, inner_payload));

        let current_count = entry.0;
        
        // Dynamically load current FEC configuration to adapt to active network conditions
        let active_k = crate::telemetry::adaptive_control::ACTIVE_FEC_K.load(std::sync::atomic::Ordering::SeqCst);
        let active_m = crate::telemetry::adaptive_control::ACTIVE_FEC_M.load(std::sync::atomic::Ordering::SeqCst);

        // If we have collected exactly K packets, we can perform full block reconstruction!
        if current_count == active_k {
            let packets_to_decode = entry.1.clone();
            // Drop blocks entry to release lock early prior to doing CPU heavy Galois Math
            drop(blocks);

            log::debug!("AetherUDP FEC Reconstruction Triggered for Block ID={} (Active K={}, M={})", block_id, active_k, active_m);
            let decoder = AetherFecDecoder::new(active_k, active_m);
            match decoder.decode_block(&packets_to_decode) {
                Ok(reconstructed_data) => {
                    let mut decoded_payloads = Vec::new();
                    for (i, p) in reconstructed_data.into_iter().enumerate() {
                        // Extract original header from reconstructed packet to find real variable length
                        if let Some(inner_header) = AetherFecHeader::deserialize(&p[0..AetherFecHeader::SIZE]) {
                            let original_len = inner_header.payload_len as usize;
                            if original_len > 0 && original_len <= p.len() - AetherFecHeader::SIZE {
                                let payload = p[AetherFecHeader::SIZE..AetherFecHeader::SIZE + original_len].to_vec();
                                decoded_payloads.push(payload);
                            }
                        } else {
                            // Parity or blank packet
                            if i < active_k {
                                decoded_payloads.push(p);
                            }
                        }
                    }

                    // Housekeep block registry history to bound memory usage
                    let mut history = self.history_ids.lock().unwrap();
                    history.push_back(block_id);
                    if history.len() > self.max_history_blocks {
                        if let Some(old_id) = history.pop_front() {
                            self.blocks.lock().unwrap().remove(&old_id);
                        }
                    }

                    log::info!("AetherUDP: Successfully reconstructed Block ID={} ({} lost packets recovered!).", block_id, active_k - packets_to_decode.iter().filter(|(idx, _)| *idx < active_k as u8).count());
                    return Some(decoded_payloads);
                }
                Err(e) => {
                    log::error!("AetherUDP FEC Reconstruction Error for Block ID={}: {}", block_id, e);
                    return None;
                }
            }
        }

        None
    }
}
