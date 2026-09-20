package dev.fandroid.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientNotificationCenterTest {
    @Test
    void newestNotificationsComeFirstAndCanBeMarkedRead() {
        ClientNotificationCenter center = new ClientNotificationCenter();
        var first = center.push(ClientNotificationCenter.Kind.SERVER_MESSAGE, "A", "one");
        var second = center.push(ClientNotificationCenter.Kind.INCOMING_CALL, "B", "two");

        assertThat(center.recent(2)).extracting(ClientNotificationCenter.Entry::id)
                .containsExactly(second.id(), first.id());
        assertThat(center.unreadCount()).isEqualTo(2);

        center.markRead(second.id());
        assertThat(center.unreadCount()).isEqualTo(1);
        assertThat(center.recent(1).getFirst().read()).isTrue();
    }

    @Test
    void markingAKindDoesNotConsumeOtherNotifications() {
        ClientNotificationCenter center = new ClientNotificationCenter();
        center.push(ClientNotificationCenter.Kind.SERVER_MESSAGE, "A", "one");
        center.push(ClientNotificationCenter.Kind.GLOBAL_MESSAGE, "B", "two");

        center.markKindRead(ClientNotificationCenter.Kind.SERVER_MESSAGE);

        assertThat(center.recent(2)).filteredOn(entry -> !entry.read())
                .extracting(ClientNotificationCenter.Entry::kind)
                .containsExactly(ClientNotificationCenter.Kind.GLOBAL_MESSAGE);
    }
}
