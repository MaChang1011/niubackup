package cn.niubackup.server;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
public class NiuServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(NiuServerApplication.class, args);
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** /admin and /admin/ land on the static admin UI page. */
    @Bean
    public WebMvcConfigurer adminPageForward() {
        return new WebMvcConfigurer() {
            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                registry.addViewController("/admin").setViewName("forward:/admin/index.html");
                registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
            }
        };
    }

    /**
     * Additional HTTP connector on the EasyTier mesh address (e.g. 10.200.0.1)
     * so paired devices can reach the control plane over the mesh while the
     * public interface stays unbound. Enabled only when nb.mesh-addr is set.
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> meshConnector(
            @Value("${nb.mesh-addr:}") String meshAddr,
            @Value("${server.port}") int port) {
        return factory -> {
            if (StringUtils.hasText(meshAddr)) {
                Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
                connector.setScheme("http");
                connector.setPort(port);
                connector.setProperty("address", meshAddr.trim());
                factory.addAdditionalTomcatConnectors(connector);
            }
        };
    }
}
