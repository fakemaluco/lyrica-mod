/// Five calibrated RMS-energy bands from the existing FFT. No second transform or capture.
pub fn levels(magnitudes: &[f32], fft_size: usize, sample_rate: u32) -> [u32; 5] {
    if fft_size == 0 || sample_rate == 0 || magnitudes.len() < 2 { return [0; 5]; }
    let nyquist = sample_rate as f64 / 2.0;
    let edges = [20.0, 180.0, 630.0, 2500.0, 8000.0, nyquist];
    std::array::from_fn(|band| {
        let first = ((edges[band].min(nyquist) * fft_size as f64 / sample_rate as f64).ceil() as usize)
            .clamp(1, magnitudes.len());
        let end = ((edges[band + 1].min(nyquist) * fft_size as f64 / sample_rate as f64).ceil() as usize)
            .clamp(first, magnitudes.len());
        let power: f64 = magnitudes[first..end].iter().filter(|v| v.is_finite())
            .map(|&v| (v as f64).powi(2)).sum();
        let rms = (2.0 * power).sqrt() / fft_size as f64;
        // -54 dBFS is the noise floor; only genuinely loud bands approach full height.
        let db = 20.0 * rms.max(1e-12).log10();
        (((db + 54.0) / 44.0).clamp(0.0, 1.0) * 10_000.0).round() as u32
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(bin: usize, amplitude: f32) -> [u32; 5] {
        let mut mags = vec![0.0; 256];
        mags[bin] = 512.0 * amplitude / 2.0;
        levels(&mags, 512, 48_000)
    }
    #[test]
    fn quiet_signals_do_not_saturate_and_noise_is_rejected() {
        assert_eq!(tone(16, 0.001), [0; 5]);
        let quiet = tone(16, 0.01)[2];
        let medium = tone(16, 0.04)[2];
        let loud = tone(16, 0.5)[2];
        assert!(quiet > 0 && quiet < medium && medium < 7000);
        assert_eq!(loud, 10_000);
    }
    #[test]
    fn frequency_bands_are_distinct_and_silence_has_no_peak_hold() {
        for (band, bin) in [1, 4, 16, 48, 128].into_iter().enumerate() {
            let result = tone(bin, 0.1);
            assert!(result[band] > 0);
            assert_eq!(result.iter().filter(|&&v| v > 0).count(), 1);
        }
        assert_eq!(levels(&[0.0; 256], 512, 48_000), [0; 5]);
        assert_eq!(levels(&[], 512, 0), [0; 5]);
    }
}
