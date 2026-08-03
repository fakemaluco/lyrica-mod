package dev.fandroid.backend;

import dev.fandroid.backend.auth.AuthService;
import dev.fandroid.backend.auth.MojangSessionVerifier;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class BackendMain {
    private BackendMain() {
    }

    public static void main(String[] args) {
        BackendConfig config = BackendConfig.fromEnvironment();
        Database database = new Database(config);
        database.migrate();
        BackendRepository repository = new BackendRepository(database.dataSource());
        AuthService auth = new AuthService(repository, new MojangSessionVerifier.Http(), config);
        BackendServer server = new BackendServer(config, repository, auth).start();

        var janitor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = Thread.ofPlatform().name("fandroid-envelope-janitor").unstarted(runnable);
            thread.setDaemon(true);
            return thread;
        });
        janitor.scheduleAtFixedRate(repository::deleteExpired, 1, 1, TimeUnit.HOURS);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            janitor.shutdownNow();
            server.close();
            database.close();
        }));
    }
}
