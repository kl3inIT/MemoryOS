package io.memoryos.retrieval.opensearch;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.Timeout;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
public class SearchInfrastructureConfiguration {
    @Bean(destroyMethod = "close")
    OpenSearchTransport searchTransport(SearchProperties properties) throws Exception {
        var host = HttpHost.create(properties.endpoint());
        var credentials = new BasicCredentialsProvider();
        if (!properties.username().isEmpty()) credentials.setCredentials(new AuthScope(host),
                new UsernamePasswordCredentials(properties.username(), properties.password().toCharArray()));
        var manager = PoolingAsyncClientConnectionManagerBuilder.create().setMaxConnTotal(16).setMaxConnPerRoute(16)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(3)).setSocketTimeout(Timeout.of(properties.timeout())).build());
        if (!properties.caCertificate().isBlank()) {
            var trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            try (InputStream input = Files.newInputStream(Path.of(properties.caCertificate()))) {
                int number = 0;
                for (var certificate : CertificateFactory.getInstance("X.509").generateCertificates(input)) {
                    trust.setCertificateEntry("opensearch-ca-" + number++, certificate);
                }
            }
            var ssl = SSLContextBuilder.create().loadTrustMaterial(trust, null).build();
            manager.setTlsStrategy(ClientTlsStrategyBuilder.create().setSslContext(ssl).buildAsync());
        }
        return ApacheHttpClient5TransportBuilder.builder(host).setMapper(new JacksonJsonpMapper())
                .setHttpClientConfigCallback(client -> client.setDefaultCredentialsProvider(credentials).setConnectionManager(manager.build()))
                .setRequestConfigCallback(request -> request.setResponseTimeout(Timeout.of(properties.timeout()))
                        .setConnectionRequestTimeout(Timeout.ofSeconds(3)))
                .build();
    }

    @Bean OpenSearchClient searchClient(OpenSearchTransport transport) { return new OpenSearchClient(transport); }
}
