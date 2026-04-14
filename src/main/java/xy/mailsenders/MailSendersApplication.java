package xy.mailsenders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MailSendersApplication {

	public static void main(String[] args) {
		SpringApplication.run(MailSendersApplication.class, args);
	}

}
