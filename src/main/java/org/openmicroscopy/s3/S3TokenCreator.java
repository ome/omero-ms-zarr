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
 * Create temporary access tokens for S3 buckets and prefixes
 *
 * @author The OME Team
 */
public class S3TokenCreator {

    /**
     * Double quote a string so it can be used as a JSON key or value
     * @param s String to be quoted
     * @return A double-quoted JSON string value
     */
    private static String d(String s) {
        return '"' + s.replace("\"", "\\\"") + '"';
    }

    /**
     * Create a JSON policy string
     * @param bucket The bucket
     * @param prefix The prefix for matching objects in the bucket
     * @return Policy as a JSON string
     */
    private String getPolicy(String bucket, String prefix) {
        // https://docs.aws.amazon.com/IAM/latest/UserGuide/access_policies.html#policies_session
        // https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_policies_elements.html
        // https://aws.amazon.com/premiumsupport/knowledge-center/s3-folder-user-access/

        String policyTemplate = "{" +
            d("Version") + ":" + d("2012-10-17") + "," +
            d("Statement") + ":[" +
                "{" +
                    d("Sid") + ":" + d("ListObjectsInBucket") + "," +
                    d("Effect") + ":" + d("Allow") + "," +
                    d("Action") + ":" + d("s3:ListBucket") + "," +
                    d("Resource") + ":[" + d("arn:aws:s3:::%s") + "]," + // bucket
                    d("Condition") + ":{" +
                        d("StringLike") + ":{" + d("s3:prefix") + ":[" + d("%s") + "]}" + // prefix
                    "}" +
                "}," +
                "{" +
                    d("Sid") + ":" + d("GetObjectsInBucket") + "," +
                    d("Effect") + ":" + d("Allow") + "," +
                    d("Action") + ":" + d("s3:GetObject") + "," +
                    d("Resource") + ":[" + d("arn:aws:s3:::%s/%s") + "]" + // bucket prefix
                "}" +
            "]" +
        "}";

        return String.format(policyTemplate, bucket, prefix, bucket, prefix);
    }

    /**
     * Request a new session token from the server that allows temporary access to objects in a bucket
     * @param endpoint The STS server endpoint
     * @param region The region, can be empty
     * @param bucket The bucket
     * @param prefix The prefix for matching objects in the bucket
     * @return Temporary access tokens
     */
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
                "{" + d("endpoint_url") + ":" + d(endpoint) +
                "," + d("region_name") + ":" + d(region) +
                "," + d("aws_access_key_id") + ":" + d(result.credentials().accessKeyId()) +
                "," + d("aws_secret_access_key") + ":" + d(result.credentials().secretAccessKey()) +
                "," + d("aws_session_token") + ":" + d(result.credentials().sessionToken()) +
                "," + d("expiration") + ":" + d(result.credentials().expiration().toString()) +
                "}";
        System.out.println(jsonOutput);
    }
}
