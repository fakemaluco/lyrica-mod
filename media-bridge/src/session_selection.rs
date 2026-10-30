/// Platform-independent policy for selecting an OS media session. A playing
/// session always beats a paused one; ties retain the last player to avoid
/// oscillation when Windows changes its global transport-controls focus.
#[derive(Debug)]
pub struct SessionCandidate {
    pub playing: bool,
    pub meaningful: bool,
    pub music_metadata: bool,
    pub previous: bool,
    pub current: bool,
}

pub fn select_session(candidates: &[SessionCandidate]) -> Option<usize> {
    candidates.iter().enumerate()
        .filter(|(_, candidate)| candidate.meaningful)
        .max_by_key(|(index, candidate)| (
            candidate.playing,
            candidate.previous,
            candidate.current,
            candidate.music_metadata,
            usize::MAX - index,
        ))
        .map(|(index, _)| index)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn candidate(playing: bool, previous: bool, current: bool) -> SessionCandidate {
        SessionCandidate { playing, meaningful: true, music_metadata: true, previous, current }
    }

    #[test]
    fn playing_app_beats_paused_current_and_previous_apps() {
        assert_eq!(select_session(&[
            candidate(false, true, true), candidate(true, false, false),
        ]), Some(1));
    }

    #[test]
    fn keeps_playing_player_when_transport_focus_changes() {
        assert_eq!(select_session(&[
            candidate(true, true, false), candidate(true, false, true),
        ]), Some(0));
    }

    #[test]
    fn empty_sessions_cannot_hide_a_real_song() {
        let mut empty = candidate(true, true, true);
        empty.meaningful = false;
        assert_eq!(select_session(&[empty, candidate(false, false, false)]), Some(1));
        assert_eq!(select_session(&[]), None);
    }
}
