/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.strimzi.api.kafka.model.KafkaSpec;
import io.strimzi.api.kafka.model.status.Condition;
import io.strimzi.operator.cluster.model.KafkaCluster;
import io.strimzi.operator.cluster.model.KafkaConfiguration;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.NodeRef;
import io.strimzi.operator.cluster.model.StorageUtils;
import io.strimzi.operator.common.operator.resource.StatusUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks for potential problems in the configuration requested by the user, to provide
 * warnings and share best practice. The intent is this class will generate warnings about
 * configurations that aren't necessarily illegal or invalid, but that could potentially
 * lead to problems.
 */
public class KafkaSpecChecker {
    private final KafkaCluster kafkaCluster;
    private final String kafkaBrokerVersion;

    // This pattern is used to extract the MAJOR.MINOR version from the protocol or format version fields
    private final static Pattern MAJOR_MINOR_REGEX = Pattern.compile("(\\d+\\.\\d+).*");

    /**
     * Constructs the SpecCheck
     *
     * @param spec          The spec requested by the user in the CR
     * @param versions      List of versions supported by the Operator. Used to detect the default version.
     * @param kafkaCluster  The model generated based on the spec. This is requested so that default
     *                      values not included in the spec can be taken into account, without needing
     *                      this class to include awareness of what defaults are applied.
     */
    public KafkaSpecChecker(KafkaSpec spec, KafkaVersion.Lookup versions, KafkaCluster kafkaCluster) {
        this.kafkaCluster = kafkaCluster;

        if (spec.getKafka().getVersion() != null) {
            this.kafkaBrokerVersion = spec.getKafka().getVersion();
        } else {
            this.kafkaBrokerVersion = versions.defaultVersion().version();
        }
    }

    /**
     * Runs the SpecChecker and returns a list of warning conditions
     *
     * @param useKRaft Flag indicating if KRaft is enabled or not. When KRaft is enabled, some additional checks
     *                 are done.
     * @return List with warning conditions
     */
    public List<Condition> run(boolean useKRaft) {
        List<Condition> warnings = new ArrayList<>();

        checkKafkaLogMessageFormatVersion(warnings);
        checkKafkaInterBrokerProtocolVersion(warnings);
        checkKafkaReplicationConfig(warnings);
        checkKafkaBrokersStorage(warnings);

        // Additional checks done for KRaft clusters
        if (useKRaft)   {
            checkKRaftControllerStorage(warnings);
            checkKRaftControllerCount(warnings);
        }

        return warnings;
    }

    /**
     * Checks if the default.replication.factor and min.insync.replicas are set. When not, adds status warnings
     * suggesting setting them.
     *
     * @param warnings List to add a warning to, if appropriate.
     */
    private void checkKafkaReplicationConfig(List<Condition> warnings) {
        String defaultReplicationFactor = kafkaCluster.getConfiguration().getConfigOption(KafkaConfiguration.DEFAULT_REPLICATION_FACTOR);
        String minInsyncReplicas = kafkaCluster.getConfiguration().getConfigOption(KafkaConfiguration.MIN_INSYNC_REPLICAS);

        if (defaultReplicationFactor == null && kafkaCluster.nodes().stream().filter(NodeRef::broker).count() > 1)   {
            warnings.add(StatusUtils.buildWarningCondition("KafkaDefaultReplicationFactor",
                    "default.replication.factor option is not configured. " +
                            "It defaults to 1 which does not guarantee reliability and availability. " +
                            "You should configure this option in .spec.kafka.config."));
        }

        if (minInsyncReplicas == null && kafkaCluster.nodes().stream().filter(NodeRef::broker).count() > 1)   {
            warnings.add(StatusUtils.buildWarningCondition("KafkaMinInsyncReplicas",
                    "min.insync.replicas option is not configured. " +
                            "It defaults to 1 which does not guarantee reliability and availability. " +
                            "You should configure this option in .spec.kafka.config."));
        }
    }

    /**
     * Checks if the version of the Kafka brokers matches any custom log.message.format.version config.
     *
     * Updating this is the final step in upgrading Kafka version, so if this doesn't match it is possibly an
     * indication that a user has updated their Kafka cluster and is unaware that they also should update
     * their format version to match.
     *
     * @param warnings List to add a warning to, if appropriate.
     */
    private void checkKafkaLogMessageFormatVersion(List<Condition> warnings) {
        String logMsgFormatVersion = kafkaCluster.getConfiguration().getConfigOption(KafkaConfiguration.LOG_MESSAGE_FORMAT_VERSION);

        if (logMsgFormatVersion != null) {
            Matcher m = MAJOR_MINOR_REGEX.matcher(logMsgFormatVersion);
            if (m.matches() && !kafkaBrokerVersion.startsWith(m.group(1))) {
                warnings.add(StatusUtils.buildWarningCondition("KafkaLogMessageFormatVersion",
                        "log.message.format.version does not match the Kafka cluster version, which suggests that an upgrade is incomplete."));
            }
        }
    }

    /**
     * Checks if the version of the Kafka brokers matches any custom inter.broker.protocol.version config.
     *
     * Updating this is the final step in upgrading Kafka version, so if this doesn't match it is possibly an
     * indication that a user has updated their Kafka cluster and is unaware that they also should update
     * their format version to match.
     *
     * @param warnings List to add a warning to, if appropriate.
     */
    private void checkKafkaInterBrokerProtocolVersion(List<Condition> warnings) {
        String interBrokerProtocolVersion = kafkaCluster.getConfiguration().getConfigOption(KafkaConfiguration.INTERBROKER_PROTOCOL_VERSION);

        if (interBrokerProtocolVersion != null) {
            Matcher m = MAJOR_MINOR_REGEX.matcher(interBrokerProtocolVersion);
            if (m.matches() && !kafkaBrokerVersion.startsWith(m.group(1))) {
                warnings.add(StatusUtils.buildWarningCondition("KafkaInterBrokerProtocolVersion",
                        "inter.broker.protocol.version does not match the Kafka cluster version, which suggests that an upgrade is incomplete."));
            }
        }
    }

    /**
     * Checks for a single-broker Kafka cluster using ephemeral storage. This is potentially a problem as it
     * means any restarts of the broker will result in data loss, as the single broker won't allow for any
     * topic replicas.
     *
     * @param warnings List to add a warning to, if appropriate.
     */
    private void checkKafkaBrokersStorage(List<Condition> warnings) {
        if (kafkaCluster.nodes().stream().filter(NodeRef::broker).count() == 1
                && StorageUtils.usesEphemeral(kafkaCluster.getStorageByPoolName().get(kafkaCluster.nodes().stream().filter(NodeRef::broker).toList().toArray(new NodeRef[]{})[0].poolName()))) {
            warnings.add(StatusUtils.buildWarningCondition("KafkaStorage",
                    "A Kafka cluster with a single broker node and ephemeral storage will lose topic messages after any restart or rolling update."));
        }
    }

    /**
     * Checks for a single-controller KRaft cluster using ephemeral storage. This is potentially a problem as it
     * means any restarts of the broker will result in data loss, as the single broker won't allow for any
     * topic replicas.
     *
     * @param warnings List to add a warning to, if appropriate.
     */
    private void checkKRaftControllerStorage(List<Condition> warnings) {
        if (kafkaCluster.nodes().stream().filter(NodeRef::controller).count() == 1
                && StorageUtils.usesEphemeral(kafkaCluster.getStorageByPoolName().get(kafkaCluster.nodes().stream().filter(NodeRef::controller).toList().toArray(new NodeRef[]{})[0].poolName()))) {
            warnings.add(StatusUtils.buildWarningCondition("KafkaStorage",
                    "A Kafka cluster with a single controller node and ephemeral storage will lose data after any restart or rolling update."));
        }
    }

    /**
     * Checks for even number of controller nodes in KRaft mode which is not recommended.
     *
     * @param warnings List to add a warning to, if appropriate.
     */
    private void checkKRaftControllerCount(List<Condition> warnings)    {
        long controllerCount = kafkaCluster.nodes().stream().filter(NodeRef::controller).count();

        if (controllerCount == 2) {
            warnings.add(StatusUtils.buildWarningCondition("KafkaKRaftControllerNodeCount",
                    "Running KRaft controller quorum with two nodes is not advisable as both nodes will be needed to avoid downtime. It is recommended that a minimum of three nodes are used."));
        } else if (controllerCount % 2 == 0) {
            warnings.add(StatusUtils.buildWarningCondition("KafkaKRaftControllerNodeCount",
                    "Running KRaft controller quorum with an odd number of nodes is recommended."));
        }
    }
}
