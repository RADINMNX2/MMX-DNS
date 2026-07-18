/// Active Queue Management (AQM) system implementing Controlled Delay (CoDel) and Token Bucket Filter (TBF) pacing.

use std::time::{Instant, Duration};
use std::collections::VecDeque;
use crate::perf::memory_pool::PoolGuard;

/// Represents a packet queued in the active queue management buffer.
pub struct QueuedPacket {
    pub guard: PoolGuard,
    pub len: usize,
    pub enqueue_time: Instant,
}

/// Token Bucket Filter (TBF) for traffic pacing.
pub struct TokenBucket {
    capacity: f64,
    tokens: f64,
    rate: f64,
    last_update: Instant,
}

impl TokenBucket {
    pub fn new(capacity: usize, rate: usize) -> Self {
        Self {
            capacity: capacity as f64,
            tokens: capacity as f64,
            rate: rate as f64,
            last_update: Instant::now(),
        }
    }

    pub fn set_rate(&mut self, rate: usize) {
        self.rate = rate as f64;
    }

    /// Checks if there are enough tokens to send a packet of specified size.
    /// Returns None if tokens are immediately available.
    /// Otherwise, returns the Duration to sleep before enough tokens are available.
    pub fn get_wait_time(&mut self, len: usize) -> Option<Duration> {
        let now = Instant::now();
        let elapsed = now.duration_since(self.last_update).as_secs_f64();
        self.last_update = now;

        // Replenish tokens based on time elapsed
        self.tokens = (self.tokens + elapsed * self.rate).min(self.capacity);

        let len_f = len as f64;
        if self.tokens >= len_f {
            None
        } else {
            let needed = len_f - self.tokens;
            let wait_secs = needed / self.rate;
            Some(Duration::from_secs_f64(wait_secs))
        }
    }

    /// Consumes tokens from the bucket.
    pub fn consume(&mut self, len: usize) {
        let len_f = len as f64;
        if self.tokens >= len_f {
            self.tokens -= len_f;
        } else {
            self.tokens = 0.0;
        }
    }
}

/// Controlled Delay (CoDel) Controller for managing non-essential queues and combating bufferbloat.
pub struct CodelQueue {
    queue: VecDeque<QueuedPacket>,
    target: Duration,
    interval: Duration,
    first_above_time: Option<Instant>,
    dropping: bool,
    drop_next: Option<Instant>,
    count: u32,
}

impl CodelQueue {
    pub fn new() -> Self {
        Self {
            queue: VecDeque::new(),
            target: Duration::from_millis(5),      // CoDel 5ms target delay
            interval: Duration::from_millis(100),  // 100ms sliding window
            first_above_time: None,
            dropping: false,
            drop_next: None,
            count: 0,
        }
    }

    pub fn push(&mut self, guard: PoolGuard, len: usize) {
        self.queue.push_back(QueuedPacket {
            guard,
            len,
            enqueue_time: Instant::now(),
        });
    }

    pub fn is_empty(&self) -> bool {
        self.queue.is_empty()
    }

    pub fn len(&self) -> usize {
        self.queue.len()
    }

    pub fn peek_len(&self) -> Option<usize> {
        self.queue.front().map(|p| p.len)
    }

    fn control_law(interval: Duration, count: u32) -> Duration {
        let count_float = (count as f64).max(1.0);
        let delay_secs = interval.as_secs_f64() / count_float.sqrt();
        Duration::from_secs_f64(delay_secs)
    }

    /// Dequeues a packet. Applying the CoDel AQM dropping state machine recursively.
    pub fn dequeue(&mut self) -> Option<QueuedPacket> {
        let now = Instant::now();
        let mut ok_to_drop = false;

        let packet = self.queue.pop_front()?;
        let sojourn_time = now.duration_since(packet.enqueue_time);

        if sojourn_time < self.target {
            // Delay is low; reset CoDel trackers
            self.first_above_time = None;
            self.dropping = false;
        } else {
            // Delay exceeds target
            if self.first_above_time.is_none() {
                self.first_above_time = Some(now + self.interval);
            } else if now >= self.first_above_time.unwrap() {
                ok_to_drop = true;
            }
        }

        if self.dropping {
            if !ok_to_drop {
                // Delay has fallen back below target; leave dropping state
                self.dropping = false;
            } else if let Some(drop_next) = self.drop_next {
                if now >= drop_next {
                    log::warn!(
                        "CoDel AQM: Dropping non-essential packet of size {} to combat bufferbloat (sojourn delay = {:?})",
                        packet.len,
                        sojourn_time
                    );
                    self.count += 1;
                    self.drop_next = Some(now + Self::control_law(self.interval, self.count));
                    
                    // Drop this packet and recursively fetch/drop the next if needed
                    return self.dequeue();
                }
            }
        } else if ok_to_drop {
            log::warn!(
                "CoDel AQM: Entering dropping state. Delay is persistently high: {:?}",
                sojourn_time
            );
            self.dropping = true;
            self.count = 1;
            self.drop_next = Some(now + Self::control_law(self.interval, self.count));
            
            // Drop this first packet and fetch/drop the next if needed
            return self.dequeue();
        }

        Some(packet)
    }
}

/// Dynamic Queue Manager orchestrating both CoDel and Token Bucket pacing.
pub struct QueueManager {
    codel_queue: CodelQueue,
    tbf: TokenBucket,
}

impl QueueManager {
    pub fn new() -> Self {
        // Initial default pacing rate: 10 Mbps (1,250,000 bytes/sec)
        // Burst capacity: 64 KB
        Self {
            codel_queue: CodelQueue::new(),
            tbf: TokenBucket::new(65536, 1250000),
        }
    }

    pub fn enqueue(&mut self, guard: PoolGuard, len: usize) {
        self.codel_queue.push(guard, len);
    }

    pub fn is_empty(&self) -> bool {
        self.codel_queue.is_empty()
    }

    pub fn get_tbf_wait_time(&mut self) -> Option<Duration> {
        let front_len = self.codel_queue.peek_len()?;
        self.tbf.get_wait_time(front_len)
    }

    pub fn dequeue_codel(&mut self) -> Option<QueuedPacket> {
        let packet = self.codel_queue.dequeue()?;
        // Consume token bucket for the dequeued packet
        self.tbf.consume(packet.len);
        Some(packet)
    }

    /// Dynamically scales Token Bucket pacing rate based on RSRP and SINR telemetry.
    pub fn update_rate_from_telemetry(&mut self) {
        let rsrp = crate::CELLULAR_METRICS.rsrp.load(std::sync::atomic::Ordering::Relaxed);
        let sinr = crate::CELLULAR_METRICS.sinr.load(std::sync::atomic::Ordering::Relaxed);

        // Map signal properties to dynamic estimated available bandwidth
        let rate_bytes_per_sec = if rsrp >= -80 && sinr >= 15 {
            50 * 1024 * 1024 / 8 // 50 Mbps
        } else if rsrp >= -95 && sinr >= 5 {
            25 * 1024 * 1024 / 8 // 25 Mbps
        } else if rsrp >= -110 && sinr >= 0 {
            10 * 1024 * 1024 / 8 // 10 Mbps
        } else {
            2 * 1024 * 1024 / 8  // 2 Mbps poor signal fallback pacing
        };

        self.tbf.set_rate(rate_bytes_per_sec);
    }
}
