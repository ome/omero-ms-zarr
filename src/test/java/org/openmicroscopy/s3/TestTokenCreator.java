package org.openmicroscopy.s3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestTokenCreator {

    static final String ENDPOINT_STR = "http://localhost:9000";
    protected URI endpoint;

    public TestTokenCreator() {
        try {
            endpoint = new URI(ENDPOINT_STR);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("The impossible has happened: " + e);
        }
    }

    S3Client requestSessionToken() {
        S3TokenCreator tokenCreator = new S3TokenCreator();
        AssumeRoleResponse response = tokenCreator.createToken(endpoint, "", "bucketa", "1/*");
        AwsSessionCredentials awsCreds = AwsSessionCredentials.create(
                response.credentials().accessKeyId(),
                response.credentials().secretAccessKey(),
                response.credentials().sessionToken());
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1) // Ignored but required by the client
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds)).build();
    }

    @Test
    @DisplayName("Test session token allowed list and read")
    void testAllowed() {
        S3Client s3 = requestSessionToken();

        List<S3Object> bucketa = s3.listObjects(ListObjectsRequest.builder()
                .bucket("bucketa")
                .prefix("1/")
                .build()).contents();
        assertEquals(bucketa.size(), 1);
        assertEquals(bucketa.get(0).key(), "1/hello.txt");

        String bucketa1hello = s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("bucketa").key("1/hello.txt").build()).asString(Charset.defaultCharset());
        assertEquals(bucketa1hello, "hello\n");
    }

    @Test
    @DisplayName("Test session token forbidden list and read")
    void testForbidden() {
        S3Client s3 = requestSessionToken();

        S3Exception throwsListBuckets = assertThrows(S3Exception.class, s3::listBuckets);
        assertTrue(throwsListBuckets.getMessage().startsWith("Access Denied."));

        S3Exception throwsListBucketa2 = assertThrows(S3Exception.class,
                () -> s3.listObjects(ListObjectsRequest.builder()
                        .bucket("bucketa")
                        .prefix("2/")
                        .build()));
        assertTrue(throwsListBucketa2.getMessage().startsWith("Access Denied."));

        S3Exception throwsListBucketb = assertThrows(S3Exception.class,
                () -> s3.listObjects(ListObjectsRequest.builder()
                        .bucket("bucketb")
                        .build()));
        assertTrue(throwsListBucketb.getMessage().startsWith("Access Denied."));

        S3Exception throwsGetObjectBucketbpony = assertThrows(S3Exception.class,
                () -> s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket("bucketb").key("pony.txt").build()));
        assertTrue(throwsGetObjectBucketbpony.getMessage().startsWith("Access Denied."));
    }
}
