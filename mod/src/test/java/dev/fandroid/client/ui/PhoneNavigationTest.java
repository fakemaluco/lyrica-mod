package dev.fandroid.client.ui;

import dev.fandroid.client.PhoneApp;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNavigationTest {
    @Test
    void backWalksToStandbyBeforeRequestingClose() {
        PhoneNavigation navigation = new PhoneNavigation();
        navigation.showMenu();
        navigation.open(PhoneApp.MUSIC);

        assertThat(navigation.back()).isFalse();
        assertThat(navigation.isMenu()).isTrue();
        assertThat(navigation.back()).isFalse();
        assertThat(navigation.isStandby()).isTrue();
        assertThat(navigation.back()).isTrue();
    }

    @Test
    void selectionWrapsAndOpeningRoutesToTheSelectedApp() {
        PhoneNavigation navigation = new PhoneNavigation();
        navigation.showMenu();
        navigation.move(-1);
        assertThat(navigation.selection()).isEqualTo(PhoneApp.CLOCK);
        navigation.open(navigation.selection());
        assertThat(navigation.app()).isEqualTo(PhoneApp.CLOCK);
        assertThat(navigation.isApp()).isTrue();
    }
}
