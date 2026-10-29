# Implementation Plan: Interactive Spiderweb Franchise Map Overhaul

Completely redesign the anime franchise chronology map into an interactive, physics-driven "spiderweb" graph (`FranchiseMapGraphView`). Users can interactively drag nodes with elastic spring physics, view the entire franchise arranged radially around the current title, and explore relationships with direct, clean connection curves free of obstacle avoidance detours.

---

## 1. Spiderweb Elastic Drag-and-Drop Physics
- **Dynamic Touch Handling**:
  - Distinguish between single-tap, map panning, pinch-to-zoom, and node dragging.
  - When touching down on a node card, capture touch and activate dragging state for that specific node.
  - As the user drags the node, convert screen coordinates to scaled canvas coordinates:
    - Update the dragged card position immediately for 1:1 responsive touch tracking.
    - Run an elastic spring simulation step: neighbor nodes connected via canonical links experience spring tension ($F = -k \cdot (\text{dist} - \text{restDist})$) with damping.
    - Connected cards gently pull and follow the dragged node like a true spiderweb.
  - On finger release (`ACTION_UP`/`ACTION_CANCEL`), run a smooth settling physics animation (`Choreographer` or `ValueAnimator`) so the spiderweb settles naturally without snapping.
  - If a drag moves cards near canvas borders, dynamically adjust virtual canvas dimensions (`contentWidth`, `contentHeight`) and clamp scroll cleanly.

---

## 2. Radial Spiderweb Auto-Layout Algorithm
- **Center on Current Anime**:
  - Identify the focal node: `franchiseData.getCurrentId()`. If absent, use the root or highest-connectivity node.
  - Place the focal node at the center of the virtual canvas `(centerX, centerY)`.
- **Concentric Radial Rings by Graph Distance & Relation**:
  - Perform Breadth-First Search (BFS) / topological ordering from the focal node.
  - Primary canon progression (prequels/sequels) aligned along a clear primary orientation axis (e.g. left-to-right or horizontal arc).
  - Side-stories, spin-offs, recaps, and alternative settings placed on concentric radial layers ($R_1, R_2, R_3$) spaced sufficiently ($~300\text{dp}$) to avoid overlapping with card bounds ($250\text{dp} \times 105\text{dp}$).
  - Run an initial repulsive relaxation pass to ensure no overlapping cards and visually balanced distribution.
- **Auto-Centering Viewport**:
  - When data loads or layout finishes, automatically center `getScrollX()` and `getScrollY()` on the current anime card so the user sees the focal title right in the middle.

---

## 3. Direct Connection Curves (No Obstacle Avoidance)
- **Removal of Obstacle Avoidance Detours**:
  - Completely strip out the legacy obstacle detection, 9-point Bezier intersection sampling, and vaulting curves (`vaultOver`, `targetApexY`, clearance margins).
- **Direct Smooth Elastic Strands**:
  - Determine optimal connection anchors on card perimeters based on relative angle between source and target cards.
  - Draw smooth, organic Bezier curves directly between source and target anchors.
  - Render end-point connection dots/arrows and maintain distinct relation color palette (purple for sequels/prequels, blue for main story, cyan for side stories, emerald for alternative versions, amber for recaps).
  - Highlight connected branch paths on card or line selection, dimming unrelated strands.

---

## 4. UI Polish & Bottom Sheet Integration
- **Interaction Feedback**:
  - Elevated elevation/shadow and subtle scale animation while dragging a node.
  - Responsive tap detection for opening anime details when tapping without dragging.
  - Zoom controls (+ / - / percentage) and legend remain fully responsive.

---

## Verification Plan
1. **Compilation Check**:
   - Run `compile_applet` to ensure error-free compilation of `FranchiseMapGraphView` and associated layout changes.
2. **Local Unit Tests**:
   - Run `gradle :app:testDebugUnitTest` to verify no regressions in player controllers or bottom sheet classes.
3. **Behavioral Verification**:
   - Test node dragging responsiveness and elastic neighbor movement.
   - Verify radial layout centering around current anime title.
   - Ensure clean direct Bezier lines without artificial obstacle detours.
