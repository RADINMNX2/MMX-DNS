use crate::fec::{gf_add, gf_mul, gf_div, AetherFecHeader};

pub struct AetherFecEncoder {
    k: usize, // Number of data packets (e.g. 10)
    m: usize, // Number of parity packets (e.g. 2)
    cauchy_matrix: Vec<Vec<u8>>,
}

impl AetherFecEncoder {
    /// Creates a new Aether FEC Encoder with K data packets and M parity packets.
    pub fn new(k: usize, m: usize) -> Self {
        assert!(k > 0 && m > 0 && k + m <= 256, "Invalid FEC block dimensions");
        
        // Construct the Cauchy Matrix of size M x K
        // C_{i, j} = 1 / (x_i ^ y_j) where x_i = i + K, y_j = j
        let mut cauchy_matrix = vec![vec![0u8; k]; m];
        for i in 0..m {
            let x_i = (i + k) as u8;
            for j in 0..k {
                let y_j = j as u8;
                let den = gf_add(x_i, y_j);
                cauchy_matrix[i][j] = gf_div(1, den);
            }
        }

        AetherFecEncoder { k, m, cauchy_matrix }
    }

    /// Encodes a complete block of exactly K data packets, generating M parity packets.
    /// All packets must be pre-padded to have identical size.
    pub fn encode_block(&self, data_packets: &[Vec<u8>], parity_packets: &mut [Vec<u8>]) {
        assert_eq!(data_packets.len(), self.k, "Must provide exactly K data packets");
        assert_eq!(parity_packets.len(), self.m, "Must provide exactly M parity packets");

        let packet_size = data_packets[0].len();
        for p in parity_packets.iter() {
            assert_eq!(p.len(), packet_size, "Parity packets must match data packet size");
        }

        // Initialize parity packets to 0
        for i in 0..self.m {
            parity_packets[i].fill(0);
        }

        // Matrix multiplication: Parity[i] = Sum_{j=0}^{K-1} ( Cauchy[i][j] * Data[j] )
        for i in 0..self.m {
            let cauchy_row = &self.cauchy_matrix[i];
            let parity_packet = &mut parity_packets[i];
            
            for j in 0..self.k {
                let factor = cauchy_row[j];
                if factor == 0 {
                    continue;
                }
                let data_packet = &data_packets[j];
                
                // Low-overhead assembly loop
                for b in 0..packet_size {
                    parity_packet[b] = gf_add(parity_packet[b], gf_mul(data_packet[b], factor));
                }
            }
        }
    }

    /// Formats a set of raw input data payloads with serialized headers, aligning their sizes
    /// with zero-padding to compute recovery blocks correctly.
    pub fn prepare_and_encode(
        &self,
        block_id: u16,
        raw_payloads: &[Vec<u8>],
    ) -> Vec<Vec<u8>> {
        let count = raw_payloads.len();
        assert!(count <= self.k, "Payload count exceeds K limits");

        // Determine max length to standardize block size
        let max_len = raw_payloads.iter().map(|p| p.len()).max().unwrap_or(0);
        let aligned_payload_size = max_len;

        // 1. Prepare data packets with embedded headers & padding
        let mut data_packets = vec![vec![0u8; AetherFecHeader::SIZE + aligned_payload_size]; self.k];
        for i in 0..self.k {
            let raw_len = if i < count { raw_payloads[i].len() } else { 0 };
            let header = AetherFecHeader {
                magic: AetherFecHeader::MAGIC,
                block_id,
                packet_index: i as u8,
                payload_len: raw_len as u16,
                reserved: 0,
            };
            
            // Serialize header
            data_packets[i][0..AetherFecHeader::SIZE].copy_from_slice(&header.serialize());
            
            // Copy payload if exists, otherwise stays zero-padded
            if i < count {
                data_packets[i][AetherFecHeader::SIZE..AetherFecHeader::SIZE + raw_len]
                    .copy_from_slice(&raw_payloads[i]);
            }
        }

        // 2. Allocate parity packets
        let mut parity_packets = vec![vec![0u8; AetherFecHeader::SIZE + aligned_payload_size]; self.m];

        // 3. Perform Cauchy RS encoding
        self.encode_block(&data_packets, &mut parity_packets);

        // 4. Inject matching headers on the generated parity blocks
        for i in 0..self.m {
            let index_in_block = (self.k + i) as u8;
            let header = AetherFecHeader {
                magic: AetherFecHeader::MAGIC,
                block_id,
                packet_index: index_in_block,
                payload_len: (AetherFecHeader::SIZE + aligned_payload_size) as u16,
                reserved: 0,
            };
            parity_packets[i][0..AetherFecHeader::SIZE].copy_from_slice(&header.serialize());
        }

        // Combine data packets and parity packets into a complete transmission set
        let mut final_blocks = data_packets;
        final_blocks.extend(parity_packets);
        final_blocks
    }
}
