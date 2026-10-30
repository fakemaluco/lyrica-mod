package dev.relaylyrics.client;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorldRenderingCompatibilityTest {
    public static class PublicIrisFixture {
        public boolean isShaderPackInUse() { return true; }
        public boolean isRenderingShadowPass() { return false; }
    }
    public static class UnavailableIrisFixture extends PublicIrisFixture {
        @Override public boolean isShaderPackInUse() { throw new IllegalStateException("API temporarily unavailable"); }
    }
    @Test void optionalPublicApiDistinguishesShadersFromShadowCamera() throws Exception {
        var api = new PublicIrisFixture();
        var bridge = new CompatibilityManager.IrisBridge(api,
                PublicIrisFixture.class.getMethod("isShaderPackInUse"), PublicIrisFixture.class.getMethod("isRenderingShadowPass"));
        assertThat(bridge.query(false)).isTrue(); assertThat(bridge.query(true)).isFalse();
    }
    @Test void missingOrFailingOptionalApiDoesNotCrashOrRetryEveryFrame() throws Exception {
        assertThat(CompatibilityManager.IrisBridge.discover(false).query(false)).isFalse();
        var bridge = new CompatibilityManager.IrisBridge(new UnavailableIrisFixture(),
                PublicIrisFixture.class.getMethod("isShaderPackInUse"), PublicIrisFixture.class.getMethod("isRenderingShadowPass"));
        assertThat(bridge.query(false)).isFalse(); assertThat(bridge.failed).isTrue();
        assertThat(bridge.query(true)).isFalse();
    }
}
