package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WorldLyricsSpatialTest {
    private static final WorldLyricsSpatial.Vector ORIGIN = vector(0, 0, 0);
    private static final WorldLyricsSpatial.Basis FRONT = WorldLyricsSpatial.Basis.facing(vector(0, 0, -1), vector(-1, 0, 0));
    private static final WorldLyricsSpatial.Camera CAMERA = new WorldLyricsSpatial.Camera(
            ORIGIN, vector(0, 0, 1), vector(0, 1, 0), 90, 1, .05);

    private static WorldLyricsSpatial.Vector vector(double x, double y, double z) {
        return new WorldLyricsSpatial.Vector(x, y, z);
    }
    private static WorldLyricsSpatial.Panel panel(double x, double y, double z, double width, double height) {
        return new WorldLyricsSpatial.Panel(vector(x, y, z), FRONT, width, height, .02, 0);
    }

    @Test void frontPanelSizeIncludesItsExtrusionAndAnimationEnvelope() {
        var plain = WorldLyricsSpatial.project(panel(0, 0, 10, 2, 1), CAMERA);
        var animated = WorldLyricsSpatial.project(new WorldLyricsSpatial.Panel(vector(0, 0, 10), FRONT, 2, 1, .02, .2), CAMERA);
        assertThat(plain.visible()).isTrue();
        assertThat(plain.nearClipped()).isFalse();
        assertThat(plain.width()).isCloseTo(2 / 9.99, within(.000001));
        assertThat(plain.height()).isCloseTo(1 / 9.99, within(.000001));
        assertThat(animated.width()).isGreaterThan(plain.width());
        assertThat(animated.height()).isGreaterThan(plain.height());
    }

    @Test void perspectiveOverlapFindsDistantPanelsThatDoNotCollideInTheWorld() {
        var near = panel(0, 0, 10, 2, 1);
        var far = panel(0, 0, 25, 4, 2);
        assertThat(WorldLyricsSpatial.worldOverlap(near, far, .1)).isFalse();
        assertThat(WorldLyricsSpatial.overlap(WorldLyricsSpatial.project(near, CAMERA), WorldLyricsSpatial.project(far, CAMERA), .15)).isTrue();
    }

    @Test void rotatedPanelHullsDoNotInheritTheirEmptyBoundingBoxCorners() {
        double root = Math.sqrt(.5);
        var diagonal = WorldLyricsSpatial.Basis.facing(vector(0, 0, -1), vector(-root, root, 0));
        var first = WorldLyricsSpatial.project(new WorldLyricsSpatial.Panel(vector(0, 0, 10), diagonal, 4, .2, .02, 0), CAMERA);
        var second = WorldLyricsSpatial.project(new WorldLyricsSpatial.Panel(vector(0, .4, 10), diagonal, 4, .2, .02, 0), CAMERA);
        assertThat(first.maxX()).isGreaterThan(second.minX());
        assertThat(first.maxY()).isGreaterThan(second.minY());
        assertThat(WorldLyricsSpatial.overlap(first, second, 0)).isFalse();
        assertThat(WorldLyricsSpatial.overlap(first, second, .15)).isFalse();
        assertThat(first.textHeight()).isLessThan(first.height() * .15);
    }

    @Test void visualMarginReservesSpaceProportionalToTheSmallerTextHeight() {
        var first = WorldLyricsSpatial.project(panel(0, 0, 10, 2, 1), CAMERA);
        var second = WorldLyricsSpatial.project(panel(2.1, 0, 10, 2, 1), CAMERA);
        assertThat(WorldLyricsSpatial.overlap(first, second, 0)).isFalse();
        assertThat(WorldLyricsSpatial.overlap(first, second, .15)).isTrue();
    }

    @Test void intersectionsOutsideTheViewportDoNotHideVisibleWords() {
        var first = new WorldLyricsSpatial.Projected(List.of(new WorldLyricsSpatial.Point(.8, .6),
                new WorldLyricsSpatial.Point(1.5, -.1), new WorldLyricsSpatial.Point(1.6, 0),
                new WorldLyricsSpatial.Point(.9, .7)), .8, -.1, 1.6, .7, 10, true, false);
        var second = new WorldLyricsSpatial.Projected(List.of(new WorldLyricsSpatial.Point(.8, -.6),
                new WorldLyricsSpatial.Point(1.5, .1), new WorldLyricsSpatial.Point(1.6, 0),
                new WorldLyricsSpatial.Point(.9, -.7)), .8, -.7, 1.6, .1, 10, true, false);
        assertThat(WorldLyricsSpatial.overlap(first, second, 0)).isFalse();
    }

    @Test void aCameraTurnCanRevealScreenOverlapWithoutChangingWorldAnchors() {
        var first = panel(-2, 0, 8, 1, 1);
        var second = panel(-6, 0, 16, 1, 1);
        assertThat(WorldLyricsSpatial.overlap(WorldLyricsSpatial.project(first, CAMERA), WorldLyricsSpatial.project(second, CAMERA), 0)).isFalse();
        // The player moves to the shared sightline while the two anchors remain fixed.
        var moved = new WorldLyricsSpatial.Camera(vector(2, 0, 0), vector(-1, 0, 2), vector(0, 1, 0), 90, 1, .05);
        assertThat(WorldLyricsSpatial.overlap(WorldLyricsSpatial.project(first, moved), WorldLyricsSpatial.project(second, moved), 0)).isTrue();
    }

    @Test void behindCameraAndOutsideViewportPanelsDoNotCauseOverlap() {
        var behind = WorldLyricsSpatial.project(panel(0, 0, -2, 2, 1), CAMERA);
        var outside = WorldLyricsSpatial.project(panel(40, 0, 10, 2, 1), CAMERA);
        assertThat(behind.visible()).isFalse();
        assertThat(behind.vertices()).isEmpty();
        assertThat(outside.visible()).isFalse();
        assertThat(WorldLyricsSpatial.overlap(behind, WorldLyricsSpatial.project(panel(0, 0, 10, 2, 1), CAMERA), .2)).isFalse();
    }

    @Test void nearPlaneIntersectionsStayFiniteAndAreMarkedForCandidateRejection() {
        var crossing = WorldLyricsSpatial.project(new WorldLyricsSpatial.Panel(vector(0, 0, .04), FRONT, 1, 1, .1, 0), CAMERA);
        assertThat(crossing.visible()).isTrue();
        assertThat(crossing.nearClipped()).isTrue();
        assertThat(crossing.vertices()).isNotEmpty().allSatisfy(point -> {
            assertThat(point.x()).isFinite();
            assertThat(point.y()).isFinite();
        });
    }

    @Test void cameraAspectAndFovChangeProjectionWithoutChangingPanelGeometry() {
        var panel = panel(0, 0, 10, 2, 1);
        var square = WorldLyricsSpatial.project(panel, CAMERA);
        var wide = WorldLyricsSpatial.project(panel, new WorldLyricsSpatial.Camera(ORIGIN, vector(0, 0, 1), vector(0, 1, 0), 90, 2, .05));
        var narrowFov = WorldLyricsSpatial.project(panel, new WorldLyricsSpatial.Camera(ORIGIN, vector(0, 0, 1), vector(0, 1, 0), 60, 1, .05));
        assertThat(wide.width()).isCloseTo(square.width() * .5, within(.000001));
        assertThat(wide.height()).isCloseTo(square.height(), within(.000001));
        assertThat(narrowFov.width()).isGreaterThan(square.width());
    }

    @Test void floorBasisIsOrthonormalAndProjectsARealHorizontalSurface() {
        var floor = WorldLyricsSpatial.Basis.facing(vector(0, 1, 0), vector(-1, 0, 0));
        assertThat(floor.right().length()).isCloseTo(1, within(.000001));
        assertThat(floor.up().length()).isCloseTo(1, within(.000001));
        assertThat(floor.normal().length()).isCloseTo(1, within(.000001));
        assertThat(floor.up().dot(floor.normal())).isCloseTo(0, within(.000001));
        assertThat(floor.right().cross(floor.up())).isEqualTo(floor.normal());
        var projected = WorldLyricsSpatial.project(new WorldLyricsSpatial.Panel(vector(0, -2, 8), floor, 3, 2, .02, 0), CAMERA);
        assertThat(projected.visible()).isTrue();
        assertThat(projected.width()).isGreaterThan(0);
        assertThat(projected.height()).isGreaterThan(0);
    }

    @Test void basisRecoversWhenPreferredRightIsParallelToTheFloorNormal() {
        var floor = WorldLyricsSpatial.Basis.facing(vector(0, 1, 0), vector(0, 1, 0));
        assertThat(floor.right().length()).isCloseTo(1, within(.000001));
        assertThat(floor.right().dot(floor.normal())).isCloseTo(0, within(.000001));
    }

    @Test void grazingAndBackFacingSurfacesFadeRatherThanStretchUnreadably() {
        assertThat(WorldLyricsSpatial.facingAlpha(panel(0, 0, 10, 2, 1), CAMERA)).isEqualTo(1);
        var grazing = new WorldLyricsSpatial.Panel(vector(0, 0, 10), WorldLyricsSpatial.Basis.facing(vector(1, 0, -.04), vector(0, 1, 0)), 2, 1, .02, 0);
        var back = new WorldLyricsSpatial.Panel(vector(0, 0, 10), WorldLyricsSpatial.Basis.facing(vector(0, 0, 1), vector(1, 0, 0)), 2, 1, .02, 0);
        assertThat(WorldLyricsSpatial.facingAlpha(grazing, CAMERA)).isZero();
        assertThat(WorldLyricsSpatial.facingAlpha(back, CAMERA)).isZero();
    }

    @Test void glassHasLowVisualPenaltyAndSparseFoliageRemainsReadable() {
        var glass = new WorldLyricsSpatial.VisualOcclusion().add(WorldLyricsSpatial.Material.GLASS, 1);
        var foliage = new WorldLyricsSpatial.VisualOcclusion().add(WorldLyricsSpatial.Material.FOLIAGE, 1);
        assertThat(glass.opacity()).isCloseTo(.04, within(.000001));
        assertThat(glass.blocked()).isFalse();
        assertThat(foliage.opacity()).isGreaterThan(glass.opacity());
        assertThat(foliage.blocked()).isFalse();
    }

    @Test void actualFoliageThicknessAccumulatesAndOpaqueWallOverridesGlass() {
        var dense = new WorldLyricsSpatial.VisualOcclusion();
        for (int layer = 0; layer < 8; layer++) dense.add(WorldLyricsSpatial.Material.FOLIAGE, .5);
        assertThat(dense.blocked()).isTrue();
        var wall = new WorldLyricsSpatial.VisualOcclusion().add(WorldLyricsSpatial.Material.GLASS, .125)
                .add(WorldLyricsSpatial.Material.OPAQUE, .01);
        assertThat(wall.opacity()).isEqualTo(1);
        assertThat(wall.blocked()).isTrue();
        var noIntersection = new WorldLyricsSpatial.VisualOcclusion().add(WorldLyricsSpatial.Material.OPAQUE, 0);
        assertThat(noIntersection.blocked()).isFalse();
    }

    @Test void thinPaneUsesItsShapeThicknessInsteadOfItsWholeBlockCell() {
        var pane = new WorldLyricsSpatial.Box(vector(0, 0, .4375), vector(1, 1, .5625));
        var coverage = WorldLyricsSpatial.coverage(vector(.5, .5, -1), vector(.5, .5, 2), List.of(pane));
        assertThat(coverage.intersects()).isTrue();
        assertThat(coverage.thickness()).isCloseTo(.125, within(.000001));
        assertThat(coverage.exitFraction()).isCloseTo(1.5625 / 3, within(.000001));
        assertThat(new WorldLyricsSpatial.VisualOcclusion().add(WorldLyricsSpatial.Material.GLASS, coverage.thickness()).opacity())
                .isLessThan(.006);
    }

    @Test void multipartShapeIntervalsAreUnitedWithoutCountingOverlapsOrEmptyGaps() {
        var first = new WorldLyricsSpatial.Box(vector(0, 0, 0), vector(1, 1, .25));
        var overlap = new WorldLyricsSpatial.Box(vector(0, 0, .125), vector(1, 1, .375));
        var separate = new WorldLyricsSpatial.Box(vector(0, 0, .75), vector(1, 1, 1));
        var coverage = WorldLyricsSpatial.coverage(vector(.5, .5, -.5), vector(.5, .5, 1.5), List.of(first, overlap, separate));
        assertThat(coverage.thickness()).isCloseTo(.625, within(.000001));
        assertThat(coverage.exitFraction()).isCloseTo(.75, within(.000001));
    }

    @Test void shapeCoverageHandlesInsideStartsReverseRaysAndMissedPartialBlocks() {
        var box = new WorldLyricsSpatial.Box(vector(0, 0, 0), vector(1, 1, 1));
        assertThat(WorldLyricsSpatial.coverage(vector(.5, .5, .5), vector(.5, .5, 2), List.of(box)).thickness())
                .isCloseTo(.5, within(.000001));
        assertThat(WorldLyricsSpatial.coverage(vector(.5, .5, 2), vector(.5, .5, -1), List.of(box)).thickness())
                .isCloseTo(1, within(.000001));
        assertThat(WorldLyricsSpatial.coverage(vector(2, .5, -1), vector(2, .5, 2), List.of(box)).intersects()).isFalse();
        assertThat(WorldLyricsSpatial.coverage(ORIGIN, ORIGIN, List.of(box)).intersects()).isFalse();
    }

    @Test void transparentVisualMaterialNeverOverridesPhysicalCollision() {
        var glass = new WorldLyricsSpatial.VisualOcclusion().add(WorldLyricsSpatial.Material.GLASS, 1);
        assertThat(glass.blocked()).isFalse();
        assertThat(WorldLyricsSpatial.worldOverlap(panel(0, 0, 10, 2, 1), panel(.5, 0, 10, 2, 1), 0)).isTrue();
        assertThat(WorldLyricsSpatial.worldOverlap(panel(0, 0, 10, 2, 1), panel(3, 0, 10, 2, 1), .1)).isFalse();
    }

    @Test void allQueriesShareOneTickBudgetAndExhaustionCanRemainUnknown() {
        var budget = new WorldLyricsSpatial.RayBudget();
        budget.beginTick(100);
        for (int i = 0; i < WorldLyricsSpatial.SEGMENTS_PER_TICK; i++) assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        assertThat(budget.used()).isEqualTo(128);
        assertThat(budget.remaining()).isZero();
        assertThat(budget.exhausted()).isTrue();
        WorldLyricsSpatial.Clearance deferred = budget.tryAcquire() ? WorldLyricsSpatial.Clearance.CLEAR : WorldLyricsSpatial.Clearance.UNKNOWN;
        assertThat(deferred).isEqualTo(WorldLyricsSpatial.Clearance.UNKNOWN);
        budget.beginTick(100);
        assertThat(budget.tryAcquire()).isFalse();
        budget.beginTick(101);
        assertThat(budget.used()).isZero();
        assertThat(budget.tryAcquire()).isTrue();
    }
}
