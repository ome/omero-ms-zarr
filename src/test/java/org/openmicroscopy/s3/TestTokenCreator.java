package org.openmicroscopy.s3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import java.net.URI;
import java.net.URISyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestTokenCreator {

    @BeforeAll
    static void beforeAll() {
        System.setProperty("aws.accessKeyId", "stsadmin");
        System.setProperty("aws.secretAccessKey", "stsadmin-secret");
    }

    @Test
    @DisplayName("Request a session token from localhost:9000")
    void requestSessionToken() throws URISyntaxException {
        S3TokenCreator calculator = new S3TokenCreator();
        AssumeRoleResponse response = calculator.createToken(new URI("http://localhost:9000"), "", "tmp", "media/*");
        assertEquals(response.credentials().accessKeyId().length(), 20);
        assertEquals(response.credentials().secretAccessKey().length(), 40);
        assertTrue(response.credentials().sessionToken().length() > 100);
    }
}
