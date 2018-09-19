/*
 * PowerAuth Server and related software components
 * Copyright (C) 2018 Wultra s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.getlime.security.powerauth.app.server.service.behavior.tasks.v2;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;
import io.getlime.security.powerauth.app.server.configuration.PowerAuthServiceConfiguration;
import io.getlime.security.powerauth.app.server.converter.v3.ServerPrivateKeyConverter;
import io.getlime.security.powerauth.app.server.database.RepositoryCatalogue;
import io.getlime.security.powerauth.app.server.database.model.ActivationStatus;
import io.getlime.security.powerauth.app.server.database.model.ServerPrivateKey;
import io.getlime.security.powerauth.app.server.database.model.entity.ActivationRecordEntity;
import io.getlime.security.powerauth.app.server.database.model.entity.ApplicationEntity;
import io.getlime.security.powerauth.app.server.database.model.entity.ApplicationVersionEntity;
import io.getlime.security.powerauth.app.server.database.model.entity.MasterKeyPairEntity;
import io.getlime.security.powerauth.app.server.database.repository.ActivationRepository;
import io.getlime.security.powerauth.app.server.database.repository.ApplicationVersionRepository;
import io.getlime.security.powerauth.app.server.database.repository.MasterKeyPairRepository;
import io.getlime.security.powerauth.app.server.service.behavior.tasks.v3.ActivationHistoryServiceBehavior;
import io.getlime.security.powerauth.app.server.service.behavior.tasks.v3.CallbackUrlBehavior;
import io.getlime.security.powerauth.app.server.service.exceptions.GenericServiceException;
import io.getlime.security.powerauth.app.server.service.i18n.LocalizationProvider;
import io.getlime.security.powerauth.app.server.service.model.ServiceError;
import io.getlime.security.powerauth.crypto.lib.generator.KeyGenerator;
import io.getlime.security.powerauth.crypto.server.activation.PowerAuthServerActivation;
import io.getlime.security.powerauth.provider.CryptoProviderUtil;
import io.getlime.security.powerauth.v2.CreateActivationResponse;
import io.getlime.security.powerauth.v2.PrepareActivationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.Date;
import java.util.Objects;
import java.util.Set;

/**
 * Behavior class implementing processes related with activations. Used to move the
 * implementation outside of the main service implementation.
 *
 * @author Petr Dvorak, petr@wultra.com
 */
@Component("ActivationServiceBehaviorV2")
public class ActivationServiceBehavior {

    private RepositoryCatalogue repositoryCatalogue;

    private CallbackUrlBehavior callbackUrlBehavior;

    private ActivationHistoryServiceBehavior activationHistoryServiceBehavior;

    private LocalizationProvider localizationProvider;

    private PowerAuthServiceConfiguration powerAuthServiceConfiguration;

    private ServerPrivateKeyConverter serverPrivateKeyConverter;

    // Prepare logger
    private static final Logger logger = LoggerFactory.getLogger(ActivationServiceBehavior.class);

    @Autowired
    public ActivationServiceBehavior(RepositoryCatalogue repositoryCatalogue, PowerAuthServiceConfiguration powerAuthServiceConfiguration) {
        this.repositoryCatalogue = repositoryCatalogue;
        this.powerAuthServiceConfiguration = powerAuthServiceConfiguration;
    }

    @Autowired
    public void setCallbackUrlBehavior(CallbackUrlBehavior callbackUrlBehavior) {
        this.callbackUrlBehavior = callbackUrlBehavior;
    }

    @Autowired
    public void setLocalizationProvider(LocalizationProvider localizationProvider) {
        this.localizationProvider = localizationProvider;
    }

    @Autowired
    public void setActivationHistoryServiceBehavior(ActivationHistoryServiceBehavior activationHistoryServiceBehavior) {
        this.activationHistoryServiceBehavior = activationHistoryServiceBehavior;
    }

    @Autowired
    public void setServerPrivateKeyConverter(ServerPrivateKeyConverter serverPrivateKeyConverter) {
        this.serverPrivateKeyConverter = serverPrivateKeyConverter;
    }

    private final PowerAuthServerActivation powerAuthServerActivation = new PowerAuthServerActivation();

    /**
     * Deactivate the activation in CREATED or OTP_USED if it's activation expiration timestamp
     * is below the given timestamp.
     *
     * @param timestamp  Timestamp to check activations against.
     * @param activation Activation to check.
     */
    private void deactivatePendingActivation(Date timestamp, ActivationRecordEntity activation) {
        if ((activation.getActivationStatus().equals(ActivationStatus.CREATED) || activation.getActivationStatus().equals(ActivationStatus.OTP_USED)) && (timestamp.getTime() > activation.getTimestampActivationExpire().getTime())) {
            activation.setActivationStatus(ActivationStatus.REMOVED);
            repositoryCatalogue.getActivationRepository().save(activation);
            activationHistoryServiceBehavior.logActivationStatusChange(activation);
            callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());
        }
    }

    /**
     * Validate provided public key and if the key is null, remove provided activation
     * (mark as REMOVED), notify callback listeners, and throw exception.
     *
     * @param activation Activation to be removed in case the device public key is not valid.
     * @param devicePublicKey Device public key to be checked.
     * @throws GenericServiceException In case provided public key is null.
     */
    private void validateNotNullPublicKey(ActivationRecordEntity activation, PublicKey devicePublicKey) throws GenericServiceException {
        if (devicePublicKey == null) { // invalid key was sent, return error
            activation.setActivationStatus(ActivationStatus.REMOVED);
            repositoryCatalogue.getActivationRepository().save(activation);
            activationHistoryServiceBehavior.logActivationStatusChange(activation);
            callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_NOT_FOUND);
        }
    }

    /**
     * Prepare activation with given parameters
     *
     * @param activationIdShort              Short activation ID
     * @param activationNonceBase64          Activation nonce encoded as Base64
     * @param clientEphemeralPublicKeyBase64 Client ephemeral public key encoded as Base64
     * @param cDevicePublicKeyBase64         Encrypted device public key encoded as Base64
     * @param activationName                 Activation name
     * @param extras                         Extra parameter
     * @param applicationKey                 Application key
     * @param applicationSignature           Application signature
     * @param keyConversionUtilities         Utility class for key conversion
     * @return Prepared activation information
     * @throws GenericServiceException      In case invalid data is provided
     * @throws InvalidKeySpecException      If invalid key was provided
     * @throws InvalidKeyException          If invalid key was provided
     * @throws UnsupportedEncodingException If UTF-8 is not supported on the system
     */
    public PrepareActivationResponse prepareActivation(String activationIdShort, String activationNonceBase64, String clientEphemeralPublicKeyBase64, String cDevicePublicKeyBase64, String activationName, String extras, String applicationKey, String applicationSignature, CryptoProviderUtil keyConversionUtilities) throws GenericServiceException, InvalidKeySpecException, InvalidKeyException, UnsupportedEncodingException {

        // Get current timestamp
        Date timestamp = new Date();

        // Get the repository
        final ActivationRepository activationRepository = repositoryCatalogue.getActivationRepository();
        final ApplicationVersionRepository applicationVersionRepository = repositoryCatalogue.getApplicationVersionRepository();

        ApplicationVersionEntity applicationVersion = applicationVersionRepository.findByApplicationKey(applicationKey);
        // if there is no such application, exit
        if (applicationVersion == null || !applicationVersion.getSupported()) {
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_EXPIRED);
        }

        ApplicationEntity application = applicationVersion.getApplication();
        // if there is no such application, exit
        if (application == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_EXPIRED);
        }

        // Fetch the current activation by short activation ID
        Set<ActivationStatus> states = ImmutableSet.of(ActivationStatus.CREATED);
        ActivationRecordEntity activation = activationRepository.findCreatedActivation(application.getId(), activationIdShort, states, timestamp);

        // Make sure to deactivate the activation if it is expired
        if (activation != null) {
            deactivatePendingActivation(timestamp, activation);
        }

        // if there is no such activation or application does not match the activation application, exit
        if (activation == null
                || !ActivationStatus.CREATED.equals(activation.getActivationStatus())
                || !Objects.equals(activation.getApplication().getId(), application.getId())) {
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_EXPIRED);
        }

        // Get master private key
        String masterPrivateKeyBase64 = activation.getMasterKeyPair().getMasterKeyPrivateBase64();
        byte[] masterPrivateKeyBytes = BaseEncoding.base64().decode(masterPrivateKeyBase64);
        PrivateKey masterPrivateKey = keyConversionUtilities.convertBytesToPrivateKey(masterPrivateKeyBytes);

        // Get client ephemeral public key
        PublicKey clientEphemeralPublicKey = null;
        if (clientEphemeralPublicKeyBase64 != null) { // additional encryption is used
            byte[] clientEphemeralPublicKeyBytes = BaseEncoding.base64().decode(clientEphemeralPublicKeyBase64);
            clientEphemeralPublicKey = keyConversionUtilities.convertBytesToPublicKey(clientEphemeralPublicKeyBytes);
        }

        // Decrypt the device public key
        byte[] C_devicePublicKey = BaseEncoding.base64().decode(cDevicePublicKeyBase64);
        byte[] activationNonce = BaseEncoding.base64().decode(activationNonceBase64);
        PublicKey devicePublicKey = powerAuthServerActivation.decryptDevicePublicKey(
                C_devicePublicKey,
                activationIdShort,
                masterPrivateKey,
                clientEphemeralPublicKey,
                activation.getActivationOTP(),
                activationNonce
        );

        validateNotNullPublicKey(activation, devicePublicKey);

        byte[] applicationSignatureBytes = BaseEncoding.base64().decode(applicationSignature);

        if (!powerAuthServerActivation.validateApplicationSignature(
                activationIdShort,
                activationNonce,
                C_devicePublicKey,
                BaseEncoding.base64().decode(applicationKey),
                BaseEncoding.base64().decode(applicationVersion.getApplicationSecret()),
                applicationSignatureBytes)) {
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_EXPIRED);
        }

        // Update and persist the activation record
        activation.setActivationStatus(ActivationStatus.OTP_USED);
        activation.setDevicePublicKeyBase64(BaseEncoding.base64().encode(keyConversionUtilities.convertPublicKeyToBytes(devicePublicKey)));
        activation.setActivationName(activationName);
        activation.setExtras(extras);
        activationRepository.save(activation);
        activationHistoryServiceBehavior.logActivationStatusChange(activation);
        callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());

        // Generate response data
        byte[] activationNonceServer = powerAuthServerActivation.generateActivationNonce();
        String serverPublicKeyBase64 = activation.getServerPublicKeyBase64();
        PublicKey serverPublicKey = keyConversionUtilities.convertBytesToPublicKey(BaseEncoding.base64().decode(serverPublicKeyBase64));
        KeyPair ephemeralKeyPair = new KeyGenerator().generateKeyPair();
        PrivateKey ephemeralPrivateKey = ephemeralKeyPair.getPrivate();
        PublicKey ephemeralPublicKey = ephemeralKeyPair.getPublic();
        byte[] ephemeralPublicKeyBytes = keyConversionUtilities.convertPublicKeyToBytes(ephemeralPublicKey);
        String activationOtp = activation.getActivationOTP();

        // Encrypt the public key
        byte[] C_serverPublicKey = powerAuthServerActivation.encryptServerPublicKey(serverPublicKey, devicePublicKey, ephemeralPrivateKey, activationOtp, activationIdShort, activationNonceServer);

        // Get encrypted public key signature
        byte[] C_serverPubKeySignature = powerAuthServerActivation.computeServerDataSignature(activation.getActivationId(), C_serverPublicKey, masterPrivateKey);
        if (C_serverPubKeySignature == null) { // in case there is a technical error with signing and null is returned, return random bytes
            C_serverPubKeySignature = new KeyGenerator().generateRandomBytes(71);
        }

        // Compute the response
        PrepareActivationResponse response = new PrepareActivationResponse();
        response.setActivationId(activation.getActivationId());
        response.setActivationNonce(BaseEncoding.base64().encode(activationNonceServer));
        response.setEncryptedServerPublicKey(BaseEncoding.base64().encode(C_serverPublicKey));
        response.setEncryptedServerPublicKeySignature(BaseEncoding.base64().encode(C_serverPubKeySignature));
        response.setEphemeralPublicKey(BaseEncoding.base64().encode(ephemeralPublicKeyBytes));

        return response;
    }

    /**
     * Prepare activation with given parameters
     *
     * @param userId                         User ID
     * @param maxFailedCount                 Maximum failed attempt count (5)
     * @param activationExpireTimestamp      Timestamp after which activation can no longer be completed
     * @param identity                       A string representing the provided identity
     * @param activationOtp                  Activation OTP parameter
     * @param activationNonceBase64          Activation nonce encoded as Base64
     * @param clientEphemeralPublicKeyBase64 Client ephemeral public key encoded as Base64
     * @param cDevicePublicKeyBase64         Encrypted device public key encoded as Base64
     * @param activationName                 Activation name
     * @param extras                         Extra parameter
     * @param applicationKey                 Application key
     * @param applicationSignature           Application signature
     * @param keyConversionUtilities         Utility class for key conversion
     * @return Prepared activation information
     * @throws GenericServiceException      In case invalid data is provided
     * @throws InvalidKeySpecException      If invalid key was provided
     * @throws InvalidKeyException          If invalid key was provided
     * @throws UnsupportedEncodingException If UTF-8 is not supported on the system
     */
    public CreateActivationResponse createActivation(
            String applicationKey,
            String userId,
            Long maxFailedCount,
            Date activationExpireTimestamp,
            String identity,
            String activationOtp,
            String activationNonceBase64,
            String clientEphemeralPublicKeyBase64,
            String cDevicePublicKeyBase64,
            String activationName,
            String extras,
            String applicationSignature,
            CryptoProviderUtil keyConversionUtilities) throws GenericServiceException, InvalidKeySpecException, InvalidKeyException, UnsupportedEncodingException {

        // Get the repository
        final ActivationRepository activationRepository = repositoryCatalogue.getActivationRepository();
        final ApplicationVersionRepository applicationVersionRepository = repositoryCatalogue.getApplicationVersionRepository();

        ApplicationVersionEntity applicationVersion = applicationVersionRepository.findByApplicationKey(applicationKey);
        // if there is no such application, exit
        if (applicationVersion == null || !applicationVersion.getSupported()) {
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_EXPIRED);
        }

        ApplicationEntity application = applicationVersion.getApplication();
        // if there is no such application, exit
        if (application == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_EXPIRED);
        }

        // Create an activation record and obtain the activation database record
        String activationId = this.initActivation(application.getId(), userId, maxFailedCount, activationExpireTimestamp, keyConversionUtilities);
        ActivationRecordEntity activation = activationRepository.findActivation(activationId);

        // Get master private key
        String masterPrivateKeyBase64 = activation.getMasterKeyPair().getMasterKeyPrivateBase64();
        byte[] masterPrivateKeyBytes = BaseEncoding.base64().decode(masterPrivateKeyBase64);
        PrivateKey masterPrivateKey = keyConversionUtilities.convertBytesToPrivateKey(masterPrivateKeyBytes);

        // Get client ephemeral public key
        PublicKey clientEphemeralPublicKey = null;
        if (clientEphemeralPublicKeyBase64 != null) { // additional encryption is used
            byte[] clientEphemeralPublicKeyBytes = BaseEncoding.base64().decode(clientEphemeralPublicKeyBase64);
            clientEphemeralPublicKey = keyConversionUtilities.convertBytesToPublicKey(clientEphemeralPublicKeyBytes);
        }

        // Decrypt the device public key
        byte[] C_devicePublicKey = BaseEncoding.base64().decode(cDevicePublicKeyBase64);
        byte[] activationNonce = BaseEncoding.base64().decode(activationNonceBase64);
        PublicKey devicePublicKey = powerAuthServerActivation.decryptDevicePublicKey(
                C_devicePublicKey,
                identity,
                masterPrivateKey,
                clientEphemeralPublicKey,
                activationOtp,
                activationNonce
        );

        validateNotNullPublicKey(activation, devicePublicKey);

        byte[] applicationSignatureBytes = BaseEncoding.base64().decode(applicationSignature);

        if (!powerAuthServerActivation.validateApplicationSignature(
                identity,
                activationNonce,
                C_devicePublicKey,
                BaseEncoding.base64().decode(applicationKey),
                BaseEncoding.base64().decode(applicationVersion.getApplicationSecret()),
                applicationSignatureBytes)) {
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_EXPIRED);
        }

        // Update and persist the activation record
        activation.setActivationStatus(ActivationStatus.OTP_USED);
        activation.setDevicePublicKeyBase64(BaseEncoding.base64().encode(keyConversionUtilities.convertPublicKeyToBytes(devicePublicKey)));
        activation.setActivationName(activationName);
        activation.setExtras(extras);
        activationRepository.save(activation);
        activationHistoryServiceBehavior.logActivationStatusChange(activation);
        callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());

        // Generate response data
        byte[] activationNonceServer = powerAuthServerActivation.generateActivationNonce();
        String serverPublicKeyBase64 = activation.getServerPublicKeyBase64();
        PublicKey serverPublicKey = keyConversionUtilities.convertBytesToPublicKey(BaseEncoding.base64().decode(serverPublicKeyBase64));
        KeyPair ephemeralKeyPair = new KeyGenerator().generateKeyPair();
        PrivateKey ephemeralPrivateKey = ephemeralKeyPair.getPrivate();
        PublicKey ephemeralPublicKey = ephemeralKeyPair.getPublic();
        byte[] ephemeralPublicKeyBytes = keyConversionUtilities.convertPublicKeyToBytes(ephemeralPublicKey);

        // Encrypt the public key
        byte[] C_serverPublicKey = powerAuthServerActivation.encryptServerPublicKey(serverPublicKey, devicePublicKey, ephemeralPrivateKey, activationOtp, identity, activationNonceServer);

        // Get encrypted public key signature
        byte[] C_serverPubKeySignature = powerAuthServerActivation.computeServerDataSignature(activation.getActivationId(), C_serverPublicKey, masterPrivateKey);
        if (C_serverPubKeySignature == null) { // in case there is a technical error with signing and null is returned, return random bytes
            C_serverPubKeySignature = new KeyGenerator().generateRandomBytes(71);
        }

        // Compute the response
        CreateActivationResponse response = new CreateActivationResponse();
        response.setActivationId(activation.getActivationId());
        response.setActivationNonce(BaseEncoding.base64().encode(activationNonceServer));
        response.setEncryptedServerPublicKey(BaseEncoding.base64().encode(C_serverPublicKey));
        response.setEncryptedServerPublicKeySignature(BaseEncoding.base64().encode(C_serverPubKeySignature));
        response.setEphemeralPublicKey(BaseEncoding.base64().encode(ephemeralPublicKeyBytes));

        return response;
    }

    private String initActivation(Long applicationId, String userId, Long maxFailedCount, Date activationExpireTimestamp, CryptoProviderUtil keyConversionUtilities) throws GenericServiceException, InvalidKeySpecException, InvalidKeyException {
        // Generate timestamp in advance
        Date timestamp = new Date();

        if (userId == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.NO_USER_ID);
        }

        if (applicationId == 0L) {
            throw localizationProvider.buildExceptionForCode(ServiceError.NO_APPLICATION_ID);
        }

        // Get the repository
        final ActivationRepository activationRepository = repositoryCatalogue.getActivationRepository();
        final MasterKeyPairRepository masterKeyPairRepository = repositoryCatalogue.getMasterKeyPairRepository();

        // Get number of max attempts from request or from constants, if not provided
        Long maxAttempt = maxFailedCount;
        if (maxAttempt == null) {
            maxAttempt = powerAuthServiceConfiguration.getSignatureMaxFailedAttempts();
        }

        // Get activation expiration date from request or from constants, if not provided
        Date timestampExpiration = activationExpireTimestamp;
        if (timestampExpiration == null) {
            timestampExpiration = new Date(timestamp.getTime() + powerAuthServiceConfiguration.getActivationValidityBeforeActive());
        }

        // Fetch the latest master private key
        MasterKeyPairEntity masterKeyPair = masterKeyPairRepository.findFirstByApplicationIdOrderByTimestampCreatedDesc(applicationId);
        if (masterKeyPair == null) {
            GenericServiceException ex = localizationProvider.buildExceptionForCode(ServiceError.NO_MASTER_SERVER_KEYPAIR);
            logger.error("No master key pair found for application ID: {}", applicationId, ex);
            throw ex;
        }
        byte[] masterPrivateKeyBytes = BaseEncoding.base64().decode(masterKeyPair.getMasterKeyPrivateBase64());
        PrivateKey masterPrivateKey = keyConversionUtilities.convertBytesToPrivateKey(masterPrivateKeyBytes);
        if (masterPrivateKey == null) {
            GenericServiceException ex = localizationProvider.buildExceptionForCode(ServiceError.INCORRECT_MASTER_SERVER_KEYPAIR_PRIVATE);
            logger.error("Master private key is invalid for application ID {} ", applicationId, ex);
            throw ex;
        }

        // Generate new activation data, generate a unique activation ID
        String activationId = null;
        for (int i = 0; i < powerAuthServiceConfiguration.getActivationGenerateActivationIdIterations(); i++) {
            String tmpActivationId = powerAuthServerActivation.generateActivationId();
            ActivationRecordEntity record = activationRepository.findActivation(tmpActivationId);
            if (record == null) {
                activationId = tmpActivationId;
                break;
            } // ... else this activation ID has a collision, reset it and try to find another one
        }
        if (activationId == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.UNABLE_TO_GENERATE_ACTIVATION_ID);
        }

        // Generate a unique short activation ID for created and OTP used states
        String activationIdShort = null;
        Set<io.getlime.security.powerauth.app.server.database.model.ActivationStatus> states = ImmutableSet.of(io.getlime.security.powerauth.app.server.database.model.ActivationStatus.CREATED, io.getlime.security.powerauth.app.server.database.model.ActivationStatus.OTP_USED);
        for (int i = 0; i < powerAuthServiceConfiguration.getActivationGenerateActivationShortIdIterations(); i++) {
            String tmpActivationIdShort = powerAuthServerActivation.generateActivationIdShort();
            ActivationRecordEntity record = activationRepository.findCreatedActivation(applicationId, tmpActivationIdShort, states, timestamp);
            // this activation short ID has a collision, reset it and find
            // another one
            if (record == null) {
                activationIdShort = tmpActivationIdShort;
                break;
            }
        }
        if (activationIdShort == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.UNABLE_TO_GENERATE_SHORT_ACTIVATION_ID);
        }

        // Generate activation OTP
        String activationOtp = powerAuthServerActivation.generateActivationOTP();

        // Compute activation signature
        byte[] activationSignature = powerAuthServerActivation.generateActivationSignature(activationIdShort, activationOtp, masterPrivateKey);

        // Happens only when there is a crypto provider setup issue (SignatureException).
        if (activationSignature == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.UNABLE_TO_COMPUTE_SIGNATURE);
        }

        // Encode the signature
        String activationSignatureBase64 = BaseEncoding.base64().encode(activationSignature);

        // Generate server key pair
        KeyPair serverKeyPair = powerAuthServerActivation.generateServerKeyPair();
        byte[] serverKeyPrivateBytes = keyConversionUtilities.convertPrivateKeyToBytes(serverKeyPair.getPrivate());
        byte[] serverKeyPublicBytes = keyConversionUtilities.convertPublicKeyToBytes(serverKeyPair.getPublic());

        // Store the new activation
        ActivationRecordEntity activation = new ActivationRecordEntity();
        activation.setActivationId(activationId);
        activation.setActivationIdShort(activationIdShort);
        activation.setActivationName(null);
        activation.setActivationOTP(activationOtp);
        activation.setActivationStatus(ActivationStatus.CREATED);
        activation.setCounter(0L);
        activation.setDevicePublicKeyBase64(null);
        activation.setExtras(null);
        activation.setFailedAttempts(0L);
        activation.setApplication(masterKeyPair.getApplication());
        activation.setMasterKeyPair(masterKeyPair);
        activation.setMaxFailedAttempts(maxAttempt);
        activation.setServerPublicKeyBase64(BaseEncoding.base64().encode(serverKeyPublicBytes));
        activation.setTimestampActivationExpire(timestampExpiration);
        activation.setTimestampCreated(timestamp);
        activation.setTimestampLastUsed(timestamp);
        // PowerAuth protocol version 2.0
        activation.setVersion(2);
        activation.setUserId(userId);

        // Convert server private key to DB columns serverPrivateKeyEncryption specifying encryption mode and serverPrivateKey with base64-encoded key.
        ServerPrivateKey serverPrivateKey = serverPrivateKeyConverter.toDBValue(serverKeyPrivateBytes, userId, activationId);
        activation.setServerPrivateKeyEncryption(serverPrivateKey.getKeyEncryptionMode());
        activation.setServerPrivateKeyBase64(serverPrivateKey.getServerPrivateKeyBase64());

        // A reference to saved ActivationRecordEntity is required when logging activation status change, otherwise issue #57 occurs on Oracle.
        activation = activationRepository.save(activation);
        activationHistoryServiceBehavior.logActivationStatusChange(activation);
        callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());

      return activationId;
    }

}
