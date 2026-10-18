import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** JRE-only readiness check; does not need curl or application credentials. */
public final class HealthCheck {
    public static void main(String[] args) {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/actuator/health"))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body().matches("(?s).*\"status\"\\s*:\\s*\"UP\".*")) {
                return;
            }
        } catch (Exception error) {
            // Docker records only the failure status, not credentials or exception data.
        }
        System.exit(1);
    }
}
