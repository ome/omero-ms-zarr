package org.openmicroscopy.s3;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import org.apache.commons.cli.*;
import java.net.URI;

import static com.google.common.base.Strings.nullToEmpty;


/**
 * A simple connection to an OMERO server using the Java gateway
 *
 * @author The OME Team
 */
public class S3TokenCreator {

    /**
     * Create a JSON policy string
     * @param bucket
     * @param prefix
     * @return Policy as a JSON string
     */
    private String getPolicy(String bucket, String prefix) {
        // https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies.html#policies_session
        // https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_elements.html
        // https://aws.amazon.com/premiumsupport/knowledge-center/s3-folder-user-access/

        String policyTemplate = "{" +
                "\"Version\": \"2012-10-17\"," +
                "\"Statement\": [" +
                "{" +
                    "\"Sid\": \"ListObjectsInBucket\"," +
                    "\"Effect\": \"Allow\"," +
                    "\"Action\": \"s3:ListBucket\"," +
                    "\"Resource\": [\"arn:aws:s3:::%s\"]," + // bucket
                    "\"Condition\": {" +
                        "\"StringLike\": { \"s3:prefix\": [\"%s\"]}" + // prefix
                    "}" +
                "}," +
                "{" +
                    "\"Sid\": \"GetObjectsInBucket\"," +
                        "\"Effect\": \"Allow\"," +
                        "\"Action\": \"s3:GetObject\"," +
                        "\"Resource\": [\"arn:aws:s3:::%s/%s\"]" + // bucket prefix
                "}" +
            "]" +
        "}";

        return String.format(policyTemplate, bucket, prefix, bucket, prefix);
    }

    public AssumeRoleResponse createToken(URI endpoint, String region, String bucket, String prefix) {
        StsClientBuilder builder = StsClient.builder();
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
            // If endpoint is set this means we're not using AWS S3, but the client requires a region even if it's ignored by the server
            if (region.isEmpty()) {
                builder.region(Region.AWS_GLOBAL);
            }
        }
        if (!region.isEmpty()) {
            builder.region(Region.of(region));
        }

        StsClient client = builder.build();

        AssumeRoleRequest.Builder request = AssumeRoleRequest.builder()
                .durationSeconds(900)
                .policy(getPolicy(bucket, prefix))
                .roleArn("arn:x:ignored:by:minio:")
                .roleSessionName("ignored-by-minio");
        return client.assumeRole(request.build());
    }

    private static void addOption(Options options, String opt, boolean required, String help) {
        Option option = new Option(opt, true, help);
        option.setRequired(required);
        options.addOption(option);
    }

    private static String dquote(String s) {
        return '"' + s.replace("\"", "\\\"") + '"';
    }

    /**
     * Create a token
     */
    public static void main(String[] args) throws Exception {
        Options options = new Options();
        addOption(options, "endpoint", false, "S3 server endpoint (optional)");
        addOption(options, "region", false, "S3 region (optional)");
        addOption(options, "bucket", true, "S3 bucket (required)");
        addOption(options, "prefix", true, "Prefix inside bucket, for example *, prefix/*, prefix/file.name (required)");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter help = new HelpFormatter();
        CommandLine cmd = null;
        try {
            cmd = parser.parse(options, args);
        } catch (ParseException e) {
            System.out.println(e.getMessage());
            String header = "Create a temporary S3 access token using the Security Token Service. " +
                    "Credentials are read using the AWS Default Credential Provider Chain.";
            help.printHelp("S3TokenCreator", header, options, "", true);
            System.exit(1);
        }

        URI endpointUri = null;
        String endpoint = nullToEmpty(cmd.getOptionValue("endpoint"));
        if (!endpoint.isEmpty()) {
            endpointUri = new URI(endpoint);
        }
        String region = nullToEmpty(cmd.getOptionValue("region"));
        S3TokenCreator client = new S3TokenCreator();
        AssumeRoleResponse result = client.createToken(
                endpointUri,
                region,
                nullToEmpty(cmd.getOptionValue("bucket")),
                nullToEmpty(cmd.getOptionValue("prefix")));

        String jsonOutput =
                "{" + dquote("endpoint_url") + ":" + dquote(endpoint) +
                "," + dquote("region_name") + ":" + dquote(region) +
                "," + dquote("aws_access_key_id") + ":" + dquote(result.credentials().accessKeyId()) +
                "," + dquote("aws_secret_access_key") + ":" + dquote(result.credentials().secretAccessKey()) +
                "," + dquote("aws_session_token") + ":" + dquote(result.credentials().sessionToken()) +
                "," + dquote("expiration") + ":" + dquote(result.credentials().expiration().toString()) +
                "}";
        System.out.println(jsonOutput);
    }
}
