package gcfv2pubsub;

import com.google.cloud.functions.CloudEventsFunction;
import com.google.events.cloud.pubsub.v1.MessagePublishedData;
import com.google.events.cloud.pubsub.v1.Message;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.cloudevents.CloudEvent;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Base64;
import java.util.Map;
import java.util.StringJoiner;
import java.util.logging.Logger;
import java.util.UUID;
import java.time.Instant;
import com.google.cloud.sql.mysql.SocketFactory;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.util.Properties;

public class EmailVerificationFunction implements CloudEventsFunction {

    private static final Logger logger = Logger.getLogger(EmailVerificationFunction.class.getName());
    private static final Gson gson = new Gson();

    private static final String MAILGUN_DOMAIN = "csyewebapp.me";
    private static final String MAILGUN_API_KEY = "6c771365f8c72c6d4e4b647c7aade9f8-309b0ef4-9b9ce17b";
    private static final String MAILGUN_API_URL = "https://api.mailgun.net/v3/" + MAILGUN_DOMAIN + "/messages";
    

    private static final String INSTANCE_CONNECTION_NAME = System.getenv("INSTANCE_CONNECTION_NAME"); 
    private static final String DB_NAME = System.getenv("DB_NAME");
    private static final String DB_USER = System.getenv("DB_USERNAME");
    private static final String DB_PASS = System.getenv("DB_PASSWORD");

    private static final String GCS_BUCKET_NAME = "webapp-verify-email-dev-bucket";
    private static final String EMAIL_TEMPLATE_FILENAME = "email_template.html";

    private static final String jdbcUrl = String.format(
            "jdbc:mysql:///%s?cloudSqlInstance=%s&" +
            "socketFactory=com.google.cloud.sql.mysql.SocketFactory",
            DB_NAME, INSTANCE_CONNECTION_NAME);
    
    

    @Override
    public void accept(CloudEvent event) {

        String cloudEventData = new String(event.getData().toBytes());
        MessagePublishedData data = gson.fromJson(cloudEventData, MessagePublishedData.class);
        Message message = data.getMessage();
        
        if (message == null || message.getData() == null) {
            logger.severe("No message data provided");
            return;
        }
        
        String decodedData = new String(Base64.getDecoder().decode(message.getData()));
        logger.info("Decoded message data: " + decodedData);
        
        JsonObject jsonObj = gson.fromJson(decodedData, JsonObject.class);
        
        if (jsonObj == null) {
            logger.severe("Failed to parse JSON object from message: " + decodedData);
            return;
        }
        
        if (!jsonObj.has("email")) {
            logger.severe("JSON object does not contain 'email': " + jsonObj);
            return;
        }

        String userEmail = jsonObj.get("email").getAsString();
        
        // Generate a unique token for email verification
        String verificationToken = generateVerificationTokenWithExpiry(2);
        String verificationLink = "http://csyewebapp.me/verify?token=" + verificationToken;

        // Before sending the email, fetching the email template content
        String emailTemplateHtml = fetchEmailTemplateFromGCS(GCS_BUCKET_NAME, EMAIL_TEMPLATE_FILENAME);
        if (emailTemplateHtml == null) {
            logger.severe("Failed to fetch email template from GCS.");
            return;
        }

        sendEmailWithMailgun(userEmail, verificationLink, verificationToken, emailTemplateHtml);
        
    }

    private String generateVerificationTokenWithExpiry(int validityDurationMinutes) {
        String token = UUID.randomUUID().toString();
        long expiryEpoch = Instant.now().plusSeconds(60 * validityDurationMinutes).getEpochSecond();
        return token + ":" + expiryEpoch;
    }

    private void sendEmailWithMailgun(String toEmail, String verificationLink, String verificationToken, String emailTemplateHtml) {
        try {
            URL url = new URL(MAILGUN_API_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            String auth = Base64.getEncoder().encodeToString(("api:" + MAILGUN_API_KEY).getBytes(StandardCharsets.UTF_8));
            connection.setRequestProperty("Authorization", "Basic " + auth);
            connection.setDoOutput(true);

            // Specify the 'From' name and email address
            String fromName = "CSYEwebapp"; 
            String fromEmail = "noreply@csyewebapp.me"; 
            String fromHeaderValue = fromName + " <" + fromEmail + ">";
            String htmlContent = emailTemplateHtml.replace("{{VERIFICATION_LINK}}", verificationLink);

            Map<String, String> parameters = Map.of(
                "from", fromHeaderValue,
                "to", toEmail,
                "subject", "Please verify your email with CSYEwebapp.",
                "text", "Please verify your email by clicking on the link: " + verificationLink + 
                        "\n\nDo not reply to this email as it is an automated message.", 
                "html", htmlContent
            );

            StringJoiner sj = new StringJoiner("&");
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                sj.add(URLEncoder.encode(entry.getKey(), "UTF-8") + "=" + URLEncoder.encode(entry.getValue(), "UTF-8"));
            }
            byte[] out = sj.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = connection.getOutputStream();
            os.write(out);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                logger.info("Email sent successfully.");
                logEmailDetails(toEmail, verificationLink, verificationToken);
            } else {
                logger.severe("Failed to send email. Response code: " + responseCode);
            }
        } catch (Exception e) {
            logger.severe("Failed to send email: " + e.getMessage());
        }
    }

    private void logEmailDetails(String userEmail, String verificationLink, String verificationToken) {
        // Use the JDBC_URL with the Cloud SQL Connector details
        Properties properties = new Properties();
        properties.setProperty("user", DB_USER);
        properties.setProperty("password", DB_PASS);
        
        try (Connection connection = DriverManager.getConnection(jdbcUrl, properties)) {
            logger.info("JDBC_URL-->"+jdbcUrl);
            String query = "INSERT INTO emails (email, link, token, sent_time) VALUES (?, ?, ?, NOW())";
            try (PreparedStatement statement = connection.prepareStatement(query)) {
                statement.setString(1, userEmail);
                statement.setString(2, verificationLink);
                statement.setString(3, verificationToken); 
                statement.executeUpdate();
                logger.info("Logged email details successfully.");
            }
        } catch (Exception e) {
            logger.severe("Failed to log email details: " + e.getMessage());
        }
    }

    private String fetchEmailTemplateFromGCS(String bucketName, String fileName) {
        Storage storage = StorageOptions.getDefaultInstance().getService();
        Blob blob = storage.get(bucketName, fileName);
        if (blob == null) {
            logger.severe("Email template blob not found in GCS.");
            return null;
        }
        return new String(blob.getContent());
    }
}