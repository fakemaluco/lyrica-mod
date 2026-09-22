package dev.relaylyrics.client.ui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WorldGlyphMeshTest {
    @Test void aSolidPixelHasSixActualFaces() {
        var faces = WorldGlyphMesh.build(new boolean[][]{{true}});
        assertThat(faces).hasSize(6);
        assertThat(faces).extracting(WorldGlyphMesh.Face::side).containsExactlyInAnyOrder(WorldGlyphMesh.Side.values());
    }

    @Test void neighboringPixelsShareTheVolumeWithoutInteriorWalls() {
        var faces = WorldGlyphMesh.build(new boolean[][]{{true, true}, {true, true}});
        assertThat(faces).hasSize(6);
        assertThat(faces.stream().filter(face -> face.side() == WorldGlyphMesh.Side.FRONT)
                .mapToInt(face -> (face.x1() - face.x0()) * (face.y1() - face.y0())).sum()).isEqualTo(4);
    }

    @Test void letterCounterIsOpenThroughTheEntireDepth() {
        var faces = WorldGlyphMesh.build(new boolean[][]{{true, true, true}, {true, false, true}, {true, true, true}});
        for (var side : new WorldGlyphMesh.Side[]{WorldGlyphMesh.Side.FRONT, WorldGlyphMesh.Side.BACK}) {
            assertThat(faces.stream().filter(face -> face.side() == side)
                    .mapToInt(face -> (face.x1() - face.x0()) * (face.y1() - face.y0())).sum()).isEqualTo(8);
            assertThat(faces.stream().filter(face -> face.side() == side)
                    .noneMatch(face -> face.x0() <= 1 && face.x1() > 1 && face.y0() <= 1 && face.y1() > 1)).isTrue();
        }
        assertThat(faces.stream().filter(face -> face.side() != WorldGlyphMesh.Side.FRONT && face.side() != WorldGlyphMesh.Side.BACK)
                .mapToInt(face -> face.x1() - face.x0() + face.y1() - face.y0()).sum()).isEqualTo(16);
    }

    @Test void emptyGlyphsHaveNoGeometryAndMalformedMasksAreRejected() {
        assertThat(WorldGlyphMesh.build(new boolean[][]{{false, false}})).isEmpty();
        assertThatThrownBy(() -> WorldGlyphMesh.build(new boolean[][]{{true}, {true, false}}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
