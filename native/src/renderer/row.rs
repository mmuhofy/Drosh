//! The row arithmetic.
//!
//! A cell's place in the frame is not its place in the viewport: the frame
//! starts with the overscan rows above the viewport. The conversion lives here,
//! on its own, because every rule that asks "which row is this cell on?" — the
//! app's selection, the search's highlights — has to get the same answer.

impl crate::renderer::frame::FrameSource {
    /// The viewport row a cell belongs to.
    ///
    /// The frame's rows start with the overscan rows above the viewport, so a
    /// cell's row in the frame is a row further down than the row the user
    /// sees. The overscan row above the viewport is row -1: it is not part of
    /// the grid the user sees, and nothing the app asks for can land on it.
    pub(super) fn viewport_row(&self, cell_index: usize) -> i64 {
        let frame_row = cell_index / self.columns.max(1) as usize;
        frame_row as i64 - i64::from(self.overscan_above)
    }
}