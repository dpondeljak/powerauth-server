/*
 * PowerAuth Server and related software components
 * Copyright (C) 2020 Wultra s.r.o.
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
package com.wultra.security.powerauth.rest.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wultra.core.rest.client.base.DefaultRestClient;
import com.wultra.core.rest.client.base.RestClient;
import com.wultra.core.rest.client.base.RestClientException;
import com.wultra.security.powerauth.client.PowerAuthClient;
import com.wultra.security.powerauth.client.model.enumeration.CallbackUrlType;
import com.wultra.security.powerauth.client.model.error.PowerAuthClientException;
import com.wultra.security.powerauth.client.model.error.PowerAuthError;
import com.wultra.security.powerauth.client.model.error.PowerAuthErrorRecovery;
import com.wultra.security.powerauth.client.model.request.*;
import com.wultra.security.powerauth.client.model.response.*;
import com.wultra.security.powerauth.client.v3.*;
import io.getlime.core.rest.model.base.request.ObjectRequest;
import io.getlime.core.rest.model.base.response.ObjectResponse;
import io.getlime.core.rest.model.base.response.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.IOException;
import java.util.*;

/**
 * Class implementing a PowerAuth REST client.
 *
 * @author Roman Strobl, roman.strobl@wultra.com
 *
 */
public class PowerAuthRestClient implements PowerAuthClient {

    private static final Logger logger = LoggerFactory.getLogger(PowerAuthRestClient.class);

    private static final String PA_REST_V3_PREFIX = "/v3";
    private static final MultiValueMap<String, String> EMPTY_MULTI_MAP = new LinkedMultiValueMap<>();

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * PowerAuth REST client constructor.
     *
     * @param baseUrl BASE URL of REST endpoints.
     */
    public PowerAuthRestClient(String baseUrl) throws PowerAuthClientException {
        this(baseUrl, new PowerAuthRestClientConfiguration());
    }

    /**
     * PowerAuth REST client constructor.
     *
     * @param baseUrl Base URL of REST endpoints.
     */
    public PowerAuthRestClient(String baseUrl, PowerAuthRestClientConfiguration config) throws PowerAuthClientException {
        DefaultRestClient.Builder builder = DefaultRestClient.builder().baseUrl(baseUrl)
                .acceptInvalidCertificate(config.getAcceptInvalidSslCertificate())
                .connectionTimeout(config.getConnectTimeout())
                .maxInMemorySize(config.getMaxMemorySize());
        if (config.isProxyEnabled()) {
            DefaultRestClient.ProxyBuilder proxyBuilder = builder.proxy().host(config.getProxyHost()).port(config.getProxyPort());
            if (config.getProxyUsername() != null) {
                proxyBuilder.username(config.getProxyUsername()).password(config.getProxyPassword());
            }
            proxyBuilder.build();
        }
        if (config.getPowerAuthClientToken() != null) {
            builder.httpBasicAuth().username(config.getPowerAuthClientToken()).password(config.getPowerAuthClientSecret()).build();
        }
        if (config.getDefaultHttpHeaders() != null) {
            builder.defaultHttpHeaders(config.getDefaultHttpHeaders());
        }
        if (config.getFilter() != null) {
            builder.filter(config.getFilter());
        }
        try {
            restClient = builder.build();
        } catch (RestClientException ex) {
            throw new PowerAuthClientException("REST client initialization failed, error: " + ex.getMessage(), ex);
        }
    }

    /**
     * Call the PowerAuth v3 API.
     *
     * @param path Path of the endpoint.
     * @param request Request object.
     * @param queryParams HTTP query parameters.
     * @param httpHeaders HTTP headers.
     * @param responseType Response type.
     * @return Response.
     */
    private <T> T callV3RestApi(String path, Object request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders, Class<T> responseType) throws PowerAuthClientException {
        ObjectRequest<?> objectRequest = new ObjectRequest<>(request);
        try {
            ObjectResponse<T> objectResponse = restClient.postObject(PA_REST_V3_PREFIX + path, objectRequest, queryParams, httpHeaders, responseType);
            return objectResponse.getResponseObject();
        } catch (RestClientException ex) {
            if (ex.getStatusCode() == null) {
                // Logging for network errors when port is closed
                logger.warn("PowerAuth service is not accessible, error: {}", ex.getMessage());
                logger.debug(ex.getMessage(), ex);
            } else if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                // Logging for 404 errors
                logger.warn("PowerAuth service is not available, error: {}", ex.getMessage());
                logger.debug(ex.getMessage(), ex);
            } else if (ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
                // Error handling for PowerAuth errors
                handleBadRequestError(ex);
            }
            // Error handling for generic HTTP errors
            throw new PowerAuthClientException(ex.getMessage(), ex);
        }
    }

    /**
     * Handle the HTTP response with BAD_REQUEST status code.
     * @param ex Exception which captured the error.
     * @throws PowerAuthClientException PowerAuth client exception.
     */
    private void handleBadRequestError(RestClientException ex) throws PowerAuthClientException {
        // Try to parse exception into PowerAuthError model class
        try {
            TypeReference<ObjectResponse<PowerAuthError>> typeReference = new TypeReference<ObjectResponse<PowerAuthError>>(){};
            ObjectResponse<PowerAuthError> error = objectMapper.readValue(ex.getResponse(), typeReference);
            if (error == null || error.getResponseObject() == null) {
                throw new PowerAuthClientException("Invalid response object");
            }
            if ("ERR_RECOVERY".equals(error.getResponseObject().getCode())) {
                // In case of special recovery errors, return PowerAuthErrorRecovery which includes additional information about recovery
                TypeReference<ObjectResponse<PowerAuthErrorRecovery>> PowerAuthErrorRecovery = new TypeReference<ObjectResponse<PowerAuthErrorRecovery>>(){};
                ObjectResponse<PowerAuthErrorRecovery> errorRecovery = objectMapper.readValue(ex.getResponse(), PowerAuthErrorRecovery);
                if (errorRecovery == null || errorRecovery.getResponseObject() == null) {
                    throw new PowerAuthClientException("Invalid response object for recovery");
                }
                throw new PowerAuthClientException(errorRecovery.getResponseObject().getMessage(), ex, errorRecovery.getResponseObject());
            }
            throw new PowerAuthClientException(error.getResponseObject().getMessage(), ex, error.getResponseObject());
        } catch (IOException ex2) {
            // Parsing failed, return a regular error
            throw new PowerAuthClientException(ex.getMessage(), ex);
        }
    }

    /**
     * Convert date to XMLGregorianCalendar
     *
     * @param date Date to be converted.
     * @return A new instance of {@link XMLGregorianCalendar}.
     */
    private XMLGregorianCalendar calendarWithDate(Date date) {
        try {
            GregorianCalendar c = new GregorianCalendar();
            c.setTime(date);
            return DatatypeFactory.newInstance().newXMLGregorianCalendar(c);
        } catch (DatatypeConfigurationException e) {
            // Unless there is a terrible configuration error, this should not happen
            logger.error("Unable to prepare a new calendar instance", e);
        }
        return null;
    }

    @Override
    public GetSystemStatusResponse getSystemStatus(GetSystemStatusRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/status", request, queryParams, httpHeaders, GetSystemStatusResponse.class);
    }

    @Override
    public GetSystemStatusResponse getSystemStatus() throws PowerAuthClientException {
        GetSystemStatusRequest request = new GetSystemStatusRequest();
        return getSystemStatus(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public GetErrorCodeListResponse getErrorList(GetErrorCodeListRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/error/list", request, queryParams, httpHeaders, GetErrorCodeListResponse.class);
    }

    @Override
    public GetErrorCodeListResponse getErrorList(String language) throws PowerAuthClientException {
        GetErrorCodeListRequest request = new GetErrorCodeListRequest();
        request.setLanguage(language);
        return getErrorList(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public InitActivationResponse initActivation(InitActivationRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/init", request, queryParams, httpHeaders, InitActivationResponse.class);
    }

    @Override
    public InitActivationResponse initActivation(String userId, Long applicationId) throws PowerAuthClientException {
        return this.initActivation(userId, applicationId, null, null, ActivationOtpValidation.NONE, null);
    }

    @Override
    public InitActivationResponse initActivation(String userId, Long applicationId, ActivationOtpValidation otpValidation, String otp) throws PowerAuthClientException {
        return this.initActivation(userId, applicationId, null, null, otpValidation, otp);
    }

    @Override
    public InitActivationResponse initActivation(String userId, Long applicationId, Long maxFailureCount, Date timestampActivationExpire) throws PowerAuthClientException {
        return this.initActivation(userId, applicationId, maxFailureCount, timestampActivationExpire, ActivationOtpValidation.NONE, null);
    }

    @Override
    public InitActivationResponse initActivation(String userId, Long applicationId, Long maxFailureCount, Date timestampActivationExpire,
                                                 ActivationOtpValidation otpValidation, String otp) throws PowerAuthClientException {
        InitActivationRequest request = new InitActivationRequest();
        request.setUserId(userId);
        request.setApplicationId(applicationId);
        request.setActivationOtpValidation(otpValidation);
        request.setActivationOtp(otp);
        if (maxFailureCount != null) {
            request.setMaxFailureCount(maxFailureCount);
        }
        if (timestampActivationExpire != null) {
            request.setTimestampActivationExpire(calendarWithDate(timestampActivationExpire));
        }
        return this.initActivation(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public PrepareActivationResponse prepareActivation(PrepareActivationRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/prepare", request, queryParams, httpHeaders, PrepareActivationResponse.class);
    }

    @Override
    public PrepareActivationResponse prepareActivation(String activationCode, String applicationKey, String ephemeralPublicKey, String encryptedData, String mac, String nonce) throws PowerAuthClientException {
        PrepareActivationRequest request = new PrepareActivationRequest();
        request.setActivationCode(activationCode);
        request.setApplicationKey(applicationKey);
        request.setEphemeralPublicKey(ephemeralPublicKey);
        request.setEncryptedData(encryptedData);
        request.setMac(mac);
        request.setNonce(nonce);
        return prepareActivation(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public CreateActivationResponse createActivation(CreateActivationRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/create", request, queryParams, httpHeaders, CreateActivationResponse.class);
    }

    @Override
    public CreateActivationResponse createActivation(String userId, Date timestampActivationExpire, Long maxFailureCount,
                                                     String applicationKey, String ephemeralPublicKey, String encryptedData,
                                                     String mac, String nonce) throws PowerAuthClientException {
        CreateActivationRequest request = new CreateActivationRequest();
        request.setUserId(userId);
        if (timestampActivationExpire != null) {
            request.setTimestampActivationExpire(calendarWithDate(timestampActivationExpire));
        }
        if (maxFailureCount != null) {
            request.setMaxFailureCount(maxFailureCount);
        }
        request.setApplicationKey(applicationKey);
        request.setEphemeralPublicKey(ephemeralPublicKey);
        request.setEncryptedData(encryptedData);
        request.setMac(mac);
        request.setNonce(nonce);
        return createActivation(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public UpdateActivationOtpResponse updateActivationOtp(String activationId, String externalUserId, String activationOtp) throws PowerAuthClientException {
        UpdateActivationOtpRequest request = new UpdateActivationOtpRequest();
        request.setActivationId(activationId);
        request.setExternalUserId(externalUserId);
        request.setActivationOtp(activationOtp);
        return updateActivationOtp(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public UpdateActivationOtpResponse updateActivationOtp(UpdateActivationOtpRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/otp/update", request, queryParams, httpHeaders, UpdateActivationOtpResponse.class);
    }

    @Override
    public CommitActivationResponse commitActivation(CommitActivationRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/commit", request, queryParams, httpHeaders, CommitActivationResponse.class);
    }

    @Override
    public CommitActivationResponse commitActivation(String activationId, String externalUserId) throws PowerAuthClientException {
        CommitActivationRequest request = new CommitActivationRequest();
        request.setActivationId(activationId);
        request.setExternalUserId(externalUserId);
        return this.commitActivation(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public CommitActivationResponse commitActivation(String activationId, String externalUserId, String activationOtp) throws PowerAuthClientException {
        CommitActivationRequest request = new CommitActivationRequest();
        request.setActivationId(activationId);
        request.setExternalUserId(externalUserId);
        request.setActivationOtp(activationOtp);
        return this.commitActivation(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public GetActivationStatusResponse getActivationStatus(GetActivationStatusRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/status", request, queryParams, httpHeaders, GetActivationStatusResponse.class);
    }

    @Override
    public GetActivationStatusResponse getActivationStatus(String activationId) throws PowerAuthClientException {
        GetActivationStatusResponse response = this.getActivationStatusWithEncryptedStatusBlob(activationId, null);
        response.setEncryptedStatusBlob(null);
        return response;
    }

    @Override
    public GetActivationStatusResponse getActivationStatusWithEncryptedStatusBlob(String activationId, String challenge) throws PowerAuthClientException {
        GetActivationStatusRequest request = new GetActivationStatusRequest();
        request.setActivationId(activationId);
        request.setChallenge(challenge);
        return this.getActivationStatus(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public RemoveActivationResponse removeActivation(RemoveActivationRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/remove", request, queryParams, httpHeaders, RemoveActivationResponse.class);
    }

    @Override
    public RemoveActivationResponse removeActivation(String activationId, String externalUserId) throws PowerAuthClientException {
        return this.removeActivation(activationId, externalUserId, false);
    }

    @Override
    public RemoveActivationResponse removeActivation(String activationId, String externalUserId, Boolean revokeRecoveryCodes) throws PowerAuthClientException {
        RemoveActivationRequest request = new RemoveActivationRequest();
        request.setActivationId(activationId);
        request.setExternalUserId(externalUserId);
        request.setRevokeRecoveryCodes(revokeRecoveryCodes);
        return this.removeActivation(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public GetActivationListForUserResponse getActivationListForUser(GetActivationListForUserRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/list", request, queryParams, httpHeaders, GetActivationListForUserResponse.class);
    }

    @Override
    public List<GetActivationListForUserResponse.Activations> getActivationListForUser(String userId) throws PowerAuthClientException {
        GetActivationListForUserRequest request = new GetActivationListForUserRequest();
        request.setUserId(userId);
        return this.getActivationListForUser(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP).getActivations();
    }

    @Override
    public LookupActivationsResponse lookupActivations(LookupActivationsRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/lookup", request, queryParams, httpHeaders, LookupActivationsResponse.class);
    }

    @Override
    public List<LookupActivationsResponse.Activations> lookupActivations(List<String> userIds, List<Long> applicationIds, Date timestampLastUsedBefore, Date timestampLastUsedAfter, ActivationStatus activationStatus, List<String> activationFlags) throws PowerAuthClientException {
        LookupActivationsRequest request = new LookupActivationsRequest();
        request.getUserIds().addAll(userIds);
        if (applicationIds != null) {
            request.getApplicationIds().addAll(applicationIds);
        }
        if (timestampLastUsedBefore != null) {
            request.setTimestampLastUsedBefore(calendarWithDate(timestampLastUsedBefore));
        }
        if (timestampLastUsedAfter != null) {
            request.setTimestampLastUsedAfter(calendarWithDate(timestampLastUsedAfter));
        }
        if (activationStatus != null) {
            request.setActivationStatus(activationStatus);
        }
        if (activationFlags != null) {
            request.getActivationFlags().addAll(activationFlags);
        }
        return this.lookupActivations(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP).getActivations();
    }

    @Override
    public UpdateStatusForActivationsResponse updateStatusForActivations(UpdateStatusForActivationsRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/status/update", request, queryParams, httpHeaders, UpdateStatusForActivationsResponse.class);
    }

    @Override
    public UpdateStatusForActivationsResponse updateStatusForActivations(List<String> activationIds, ActivationStatus activationStatus) throws PowerAuthClientException {
        UpdateStatusForActivationsRequest request = new UpdateStatusForActivationsRequest();
        request.getActivationIds().addAll(activationIds);
        if (activationStatus != null) {
            request.setActivationStatus(activationStatus);
        }
        return this.updateStatusForActivations(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public VerifySignatureResponse verifySignature(VerifySignatureRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/signature/verify", request, queryParams, httpHeaders, VerifySignatureResponse.class);
    }

    @Override
    public VerifySignatureResponse verifySignature(String activationId, String applicationKey, String data, String signature, SignatureType signatureType, String signatureVersion, Long forcedSignatureVersion) throws PowerAuthClientException {
        VerifySignatureRequest request = new VerifySignatureRequest();
        request.setActivationId(activationId);
        request.setApplicationKey(applicationKey);
        request.setData(data);
        request.setSignature(signature);
        request.setSignatureType(signatureType);
        request.setSignatureVersion(signatureVersion);
        request.setForcedSignatureVersion(forcedSignatureVersion);
        return this.verifySignature(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public CreatePersonalizedOfflineSignaturePayloadResponse createPersonalizedOfflineSignaturePayload(CreatePersonalizedOfflineSignaturePayloadRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/signature/offline/personalized/create", request, queryParams, httpHeaders, CreatePersonalizedOfflineSignaturePayloadResponse.class);
    }

    @Override
    public CreatePersonalizedOfflineSignaturePayloadResponse createPersonalizedOfflineSignaturePayload(String activationId, String data) throws PowerAuthClientException {
        CreatePersonalizedOfflineSignaturePayloadRequest request = new CreatePersonalizedOfflineSignaturePayloadRequest();
        request.setActivationId(activationId);
        request.setData(data);
        return createPersonalizedOfflineSignaturePayload(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public CreateNonPersonalizedOfflineSignaturePayloadResponse createNonPersonalizedOfflineSignaturePayload(CreateNonPersonalizedOfflineSignaturePayloadRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/signature/offline/non-personalized/create", request, queryParams, httpHeaders, CreateNonPersonalizedOfflineSignaturePayloadResponse.class);
    }

    @Override
    public CreateNonPersonalizedOfflineSignaturePayloadResponse createNonPersonalizedOfflineSignaturePayload(long applicationId, String data) throws PowerAuthClientException {
        CreateNonPersonalizedOfflineSignaturePayloadRequest request = new CreateNonPersonalizedOfflineSignaturePayloadRequest();
        request.setApplicationId(applicationId);
        request.setData(data);
        return createNonPersonalizedOfflineSignaturePayload(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public VerifyOfflineSignatureResponse verifyOfflineSignature(VerifyOfflineSignatureRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/signature/offline/verify", request, queryParams, httpHeaders, VerifyOfflineSignatureResponse.class);
    }

    @Override
    public VerifyOfflineSignatureResponse verifyOfflineSignature(String activationId, String data, String signature, boolean allowBiometry) throws PowerAuthClientException {
        VerifyOfflineSignatureRequest request = new VerifyOfflineSignatureRequest();
        request.setActivationId(activationId);
        request.setData(data);
        request.setSignature(signature);
        request.setAllowBiometry(allowBiometry);
        return verifyOfflineSignature(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public VaultUnlockResponse unlockVault(VaultUnlockRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/vault/unlock", request, queryParams, httpHeaders, VaultUnlockResponse.class);
    }

    @Override
    public VaultUnlockResponse unlockVault(String activationId, String applicationKey, String signature,
                                           SignatureType signatureType, String signatureVersion, String signedData,
                                           String ephemeralPublicKey, String encryptedData, String mac, String nonce) throws PowerAuthClientException {
        VaultUnlockRequest request = new VaultUnlockRequest();
        request.setActivationId(activationId);
        request.setApplicationKey(applicationKey);
        request.setSignedData(signedData);
        request.setSignature(signature);
        request.setSignatureType(signatureType);
        request.setSignatureVersion(signatureVersion);
        request.setEphemeralPublicKey(ephemeralPublicKey);
        request.setEncryptedData(encryptedData);
        request.setMac(mac);
        request.setNonce(nonce);
        return unlockVault(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public VerifyECDSASignatureResponse verifyECDSASignature(VerifyECDSASignatureRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/signature/ecdsa/verify", request, queryParams, httpHeaders, VerifyECDSASignatureResponse.class);
    }

    @Override
    public VerifyECDSASignatureResponse verifyECDSASignature(String activationId, String data, String signature) throws PowerAuthClientException {
        VerifyECDSASignatureRequest request = new VerifyECDSASignatureRequest();
        request.setActivationId(activationId);
        request.setData(data);
        request.setSignature(signature);
        return this.verifyECDSASignature(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public SignatureAuditResponse getSignatureAuditLog(SignatureAuditRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/signature/list", request, queryParams, httpHeaders, SignatureAuditResponse.class);
    }

    @Override
    public List<SignatureAuditResponse.Items> getSignatureAuditLog(String userId, Date startingDate, Date endingDate) throws PowerAuthClientException {
        SignatureAuditRequest request = new SignatureAuditRequest();
        request.setUserId(userId);
        request.setTimestampFrom(calendarWithDate(startingDate));
        request.setTimestampTo(calendarWithDate(endingDate));
        return this.getSignatureAuditLog(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP).getItems();
    }

    @Override
    public List<SignatureAuditResponse.Items> getSignatureAuditLog(String userId, Long applicationId, Date startingDate, Date endingDate) throws PowerAuthClientException {
        SignatureAuditRequest request = new SignatureAuditRequest();
        request.setUserId(userId);
        request.setApplicationId(applicationId);
        request.setTimestampFrom(calendarWithDate(startingDate));
        request.setTimestampTo(calendarWithDate(endingDate));
        return this.getSignatureAuditLog(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP).getItems();
    }

    @Override
    public ActivationHistoryResponse getActivationHistory(ActivationHistoryRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/history", request, queryParams, httpHeaders, ActivationHistoryResponse.class);
    }

    @Override
    public List<ActivationHistoryResponse.Items> getActivationHistory(String activationId, Date startingDate, Date endingDate) throws PowerAuthClientException {
        ActivationHistoryRequest request = new ActivationHistoryRequest();
        request.setActivationId(activationId);
        request.setTimestampFrom(calendarWithDate(startingDate));
        request.setTimestampTo(calendarWithDate(endingDate));
        return this.getActivationHistory(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP).getItems();
    }

    @Override
    public BlockActivationResponse blockActivation(BlockActivationRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/block", request, queryParams, httpHeaders, BlockActivationResponse.class);
    }

    @Override
    public BlockActivationResponse blockActivation(String activationId, String reason, String externalUserId) throws PowerAuthClientException {
        BlockActivationRequest request = new BlockActivationRequest();
        request.setActivationId(activationId);
        request.setReason(reason);
        request.setExternalUserId(externalUserId);
        return this.blockActivation(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public UnblockActivationResponse unblockActivation(UnblockActivationRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/unblock", request, queryParams, httpHeaders, UnblockActivationResponse.class);
    }

    @Override
    public UnblockActivationResponse unblockActivation(String activationId, String externalUserId) throws PowerAuthClientException {
        UnblockActivationRequest request = new UnblockActivationRequest();
        request.setActivationId(activationId);
        request.setExternalUserId(externalUserId);
        return this.unblockActivation(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public GetApplicationListResponse getApplicationList(GetApplicationListRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/list", request, queryParams, httpHeaders, GetApplicationListResponse.class);
    }

    @Override
    public List<GetApplicationListResponse.Applications> getApplicationList() throws PowerAuthClientException {
        return this.getApplicationList(new GetApplicationListRequest(), EMPTY_MULTI_MAP, EMPTY_MULTI_MAP).getApplications();
    }

    @Override
    public GetApplicationDetailResponse getApplicationDetail(GetApplicationDetailRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/detail", request, queryParams, httpHeaders, GetApplicationDetailResponse.class);
    }

    @Override
    public GetApplicationDetailResponse getApplicationDetail(Long applicationId) throws PowerAuthClientException {
        GetApplicationDetailRequest request = new GetApplicationDetailRequest();
        request.setApplicationId(applicationId);
        return this.getApplicationDetail(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public GetApplicationDetailResponse getApplicationDetail(String applicationName) throws PowerAuthClientException {
        GetApplicationDetailRequest request = new GetApplicationDetailRequest();
        request.setApplicationName(applicationName);
        return this.getApplicationDetail(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public LookupApplicationByAppKeyResponse lookupApplicationByAppKey(LookupApplicationByAppKeyRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/detail/version", request, queryParams, httpHeaders, LookupApplicationByAppKeyResponse.class);
    }

    @Override
    public LookupApplicationByAppKeyResponse lookupApplicationByAppKey(String applicationKey) throws PowerAuthClientException {
        LookupApplicationByAppKeyRequest request = new LookupApplicationByAppKeyRequest();
        request.setApplicationKey(applicationKey);
        return this.lookupApplicationByAppKey(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public CreateApplicationResponse createApplication(CreateApplicationRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/create", request, queryParams, httpHeaders, CreateApplicationResponse.class);
    }

    @Override
    public CreateApplicationResponse createApplication(String name) throws PowerAuthClientException {
        CreateApplicationRequest request = new CreateApplicationRequest();
        request.setApplicationName(name);
        return this.createApplication(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public CreateApplicationVersionResponse createApplicationVersion(CreateApplicationVersionRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/version/create", request, queryParams, httpHeaders, CreateApplicationVersionResponse.class);
    }

    @Override
    public CreateApplicationVersionResponse createApplicationVersion(Long applicationId, String versionName) throws PowerAuthClientException {
        CreateApplicationVersionRequest request = new CreateApplicationVersionRequest();
        request.setApplicationId(applicationId);
        request.setApplicationVersionName(versionName);
        return this.createApplicationVersion(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public UnsupportApplicationVersionResponse unsupportApplicationVersion(UnsupportApplicationVersionRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/version/unsupport", request, queryParams, httpHeaders, UnsupportApplicationVersionResponse.class);
    }

    @Override
    public UnsupportApplicationVersionResponse unsupportApplicationVersion(Long versionId) throws PowerAuthClientException {
        UnsupportApplicationVersionRequest request = new UnsupportApplicationVersionRequest();
        request.setApplicationVersionId(versionId);
        return this.unsupportApplicationVersion(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public SupportApplicationVersionResponse supportApplicationVersion(SupportApplicationVersionRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/version/support", request, queryParams, httpHeaders, SupportApplicationVersionResponse.class);
    }

    @Override
    public SupportApplicationVersionResponse supportApplicationVersion(Long versionId) throws PowerAuthClientException {
        SupportApplicationVersionRequest request = new SupportApplicationVersionRequest();
        request.setApplicationVersionId(versionId);
        return this.supportApplicationVersion(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public CreateIntegrationResponse createIntegration(CreateIntegrationRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/integration/create", request, queryParams, httpHeaders, CreateIntegrationResponse.class);
    }

    @Override
    public CreateIntegrationResponse createIntegration(String name) throws PowerAuthClientException {
        CreateIntegrationRequest request = new CreateIntegrationRequest();
        request.setName(name);
        return this.createIntegration(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public GetIntegrationListResponse getIntegrationList(GetIntegrationListRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/integration/list", request, queryParams, httpHeaders, GetIntegrationListResponse.class);
    }

    @Override
    public List<GetIntegrationListResponse.Items> getIntegrationList() throws PowerAuthClientException {
        return this.getIntegrationList(new GetIntegrationListRequest(), EMPTY_MULTI_MAP, EMPTY_MULTI_MAP).getItems();
    }

    @Override
    public RemoveIntegrationResponse removeIntegration(RemoveIntegrationRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/integration/remove", request, queryParams, httpHeaders, RemoveIntegrationResponse.class);
    }

    @Override
    public RemoveIntegrationResponse removeIntegration(String id) throws PowerAuthClientException {
        RemoveIntegrationRequest request = new RemoveIntegrationRequest();
        request.setId(id);
        return this.removeIntegration(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public CreateCallbackUrlResponse createCallbackUrl(CreateCallbackUrlRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/callback/create", request, queryParams, httpHeaders, CreateCallbackUrlResponse.class);
    }

    @Override
    public CreateCallbackUrlResponse createCallbackUrl(Long applicationId, String name, CallbackUrlType type, String callbackUrl, List<String> attributes, HttpAuthenticationPrivate authentication) throws PowerAuthClientException {
        CreateCallbackUrlRequest request = new CreateCallbackUrlRequest();
        request.setApplicationId(applicationId);
        request.setName(name);
        request.setType(type.toString());
        request.setCallbackUrl(callbackUrl);
        if (attributes != null) {
            request.getAttributes().addAll(attributes);
        }
        request.setAuthentication(authentication);
        return this.createCallbackUrl(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public UpdateCallbackUrlResponse updateCallbackUrl(UpdateCallbackUrlRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/callback/update", request, queryParams, httpHeaders, UpdateCallbackUrlResponse.class);
    }

    @Override
    public UpdateCallbackUrlResponse updateCallbackUrl(String id, long applicationId, String name, String callbackUrl, List<String> attributes, HttpAuthenticationPrivate authentication) throws PowerAuthClientException {
        UpdateCallbackUrlRequest request = new UpdateCallbackUrlRequest();
        request.setId(id);
        request.setApplicationId(applicationId);
        request.setName(name);
        request.setCallbackUrl(callbackUrl);
        if (attributes != null) {
            request.getAttributes().addAll(attributes);
        }
        request.setAuthentication(authentication);
        return this.updateCallbackUrl(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public GetCallbackUrlListResponse getCallbackUrlList(GetCallbackUrlListRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/callback/list", request, queryParams, httpHeaders, GetCallbackUrlListResponse.class);
    }

    @Override
    public List<GetCallbackUrlListResponse.CallbackUrlList> getCallbackUrlList(Long applicationId) throws PowerAuthClientException {
        GetCallbackUrlListRequest request = new GetCallbackUrlListRequest();
        request.setApplicationId(applicationId);
        return getCallbackUrlList(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP).getCallbackUrlList();
    }

    @Override
    public RemoveCallbackUrlResponse removeCallbackUrl(RemoveCallbackUrlRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/callback/remove", request, queryParams, httpHeaders, RemoveCallbackUrlResponse.class);
    }

    @Override
    public RemoveCallbackUrlResponse removeCallbackUrl(String callbackUrlId) throws PowerAuthClientException {
        RemoveCallbackUrlRequest request = new RemoveCallbackUrlRequest();
        request.setId(callbackUrlId);
        return removeCallbackUrl(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public CreateTokenResponse createToken(CreateTokenRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/token/create", request, queryParams, httpHeaders, CreateTokenResponse.class);
    }

    @Override
    public CreateTokenResponse createToken(String activationId, String applicationKey, String ephemeralPublicKey,
                                           String encryptedData, String mac, String nonce, SignatureType signatureType) throws PowerAuthClientException {
        CreateTokenRequest request = new CreateTokenRequest();
        request.setActivationId(activationId);
        request.setApplicationKey(applicationKey);
        request.setEncryptedData(encryptedData);
        request.setMac(mac);
        request.setEphemeralPublicKey(ephemeralPublicKey);
        request.setNonce(nonce);
        request.setSignatureType(signatureType);
        return createToken(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public ValidateTokenResponse validateToken(ValidateTokenRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/token/validate", request, queryParams, httpHeaders, ValidateTokenResponse.class);
    }

    @Override
    public ValidateTokenResponse validateToken(String tokenId, String nonce, long timestamp, String tokenDigest) throws PowerAuthClientException {
        ValidateTokenRequest request = new ValidateTokenRequest();
        request.setTokenId(tokenId);
        request.setNonce(nonce);
        request.setTimestamp(timestamp);
        request.setTokenDigest(tokenDigest);
        return validateToken(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public RemoveTokenResponse removeToken(RemoveTokenRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/token/remove", request, queryParams, httpHeaders, RemoveTokenResponse.class);
    }

    @Override
    public RemoveTokenResponse removeToken(String tokenId, String activationId) throws PowerAuthClientException {
        RemoveTokenRequest request = new RemoveTokenRequest();
        request.setTokenId(tokenId);
        request.setActivationId(activationId);
        return removeToken(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public GetEciesDecryptorResponse getEciesDecryptor(GetEciesDecryptorRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/ecies/decryptor", request, queryParams, httpHeaders, GetEciesDecryptorResponse.class);
    }

    @Override
    public GetEciesDecryptorResponse getEciesDecryptor(String activationId, String applicationKey, String ephemeralPublicKey) throws PowerAuthClientException {
        GetEciesDecryptorRequest request = new GetEciesDecryptorRequest();
        request.setActivationId(activationId);
        request.setApplicationKey(applicationKey);
        request.setEphemeralPublicKey(ephemeralPublicKey);
        return getEciesDecryptor(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public StartUpgradeResponse startUpgrade(StartUpgradeRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/upgrade/start", request, queryParams, httpHeaders, StartUpgradeResponse.class);
    }

    @Override
    public StartUpgradeResponse startUpgrade(String activationId, String applicationKey, String ephemeralPublicKey,
                                             String encryptedData, String mac, String nonce) throws PowerAuthClientException {
        StartUpgradeRequest request = new StartUpgradeRequest();
        request.setActivationId(activationId);
        request.setApplicationKey(applicationKey);
        request.setEphemeralPublicKey(ephemeralPublicKey);
        request.setEncryptedData(encryptedData);
        request.setMac(mac);
        request.setNonce(nonce);
        return startUpgrade(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public CommitUpgradeResponse commitUpgrade(CommitUpgradeRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/upgrade/commit", request, queryParams, httpHeaders, CommitUpgradeResponse.class);
    }

    @Override
    public CommitUpgradeResponse commitUpgrade(String activationId, String applicationKey) throws PowerAuthClientException {
        CommitUpgradeRequest request = new CommitUpgradeRequest();
        request.setActivationId(activationId);
        request.setApplicationKey(applicationKey);
        return commitUpgrade(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public CreateRecoveryCodeResponse createRecoveryCode(CreateRecoveryCodeRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/recovery/create", request, queryParams, httpHeaders, CreateRecoveryCodeResponse.class);
    }

    @Override
    public CreateRecoveryCodeResponse createRecoveryCode(Long applicationId, String userId, Long pukCount) throws PowerAuthClientException {
        CreateRecoveryCodeRequest request = new CreateRecoveryCodeRequest();
        request.setApplicationId(applicationId);
        request.setUserId(userId);
        request.setPukCount(pukCount);
        return createRecoveryCode(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public ConfirmRecoveryCodeResponse confirmRecoveryCode(ConfirmRecoveryCodeRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/recovery/confirm", request, queryParams, httpHeaders, ConfirmRecoveryCodeResponse.class);
    }

    @Override
    public ConfirmRecoveryCodeResponse confirmRecoveryCode(String activationId, String applicationKey, String ephemeralPublicKey,
                                                           String encryptedData, String mac, String nonce) throws PowerAuthClientException {
        ConfirmRecoveryCodeRequest request = new ConfirmRecoveryCodeRequest();
        request.setActivationId(activationId);
        request.setApplicationKey(applicationKey);
        request.setEphemeralPublicKey(ephemeralPublicKey);
        request.setEncryptedData(encryptedData);
        request.setMac(mac);
        request.setNonce(nonce);
        return confirmRecoveryCode(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public LookupRecoveryCodesResponse lookupRecoveryCodes(LookupRecoveryCodesRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/recovery/lookup", request, queryParams, httpHeaders, LookupRecoveryCodesResponse.class);
    }

    @Override
    public LookupRecoveryCodesResponse lookupRecoveryCodes(String userId, String activationId, Long applicationId,
                                                           RecoveryCodeStatus recoveryCodeStatus, RecoveryPukStatus recoveryPukStatus) throws PowerAuthClientException {
        LookupRecoveryCodesRequest request = new LookupRecoveryCodesRequest();
        request.setUserId(userId);
        request.setActivationId(activationId);
        request.setApplicationId(applicationId);
        request.setRecoveryCodeStatus(recoveryCodeStatus);
        request.setRecoveryPukStatus(recoveryPukStatus);
        return lookupRecoveryCodes(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public RevokeRecoveryCodesResponse revokeRecoveryCodes(RevokeRecoveryCodesRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/recovery/revoke", request, queryParams, httpHeaders, RevokeRecoveryCodesResponse.class);
    }

    @Override
    public RevokeRecoveryCodesResponse revokeRecoveryCodes(List<Long> recoveryCodeIds) throws PowerAuthClientException {
        RevokeRecoveryCodesRequest request = new RevokeRecoveryCodesRequest();
        request.getRecoveryCodeIds().addAll(recoveryCodeIds);
        return revokeRecoveryCodes(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public RecoveryCodeActivationResponse createActivationUsingRecoveryCode(RecoveryCodeActivationRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/recovery/create", request, queryParams, httpHeaders, RecoveryCodeActivationResponse.class);
    }

    @Override
    public RecoveryCodeActivationResponse createActivationUsingRecoveryCode(String recoveryCode, String puk, String applicationKey, Long maxFailureCount,
                                                                            String ephemeralPublicKey, String encryptedData, String mac, String nonce) throws PowerAuthClientException {
        RecoveryCodeActivationRequest request = new RecoveryCodeActivationRequest();
        request.setRecoveryCode(recoveryCode);
        request.setPuk(puk);
        request.setApplicationKey(applicationKey);
        if (maxFailureCount != null) {
            request.setMaxFailureCount(maxFailureCount);
        }
        request.setEphemeralPublicKey(ephemeralPublicKey);
        request.setEncryptedData(encryptedData);
        request.setMac(mac);
        request.setNonce(nonce);
        return createActivationUsingRecoveryCode(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public GetRecoveryConfigResponse getRecoveryConfig(GetRecoveryConfigRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/recovery/config/detail", request, queryParams, httpHeaders, GetRecoveryConfigResponse.class);
    }

    @Override
    public GetRecoveryConfigResponse getRecoveryConfig(Long applicationId) throws PowerAuthClientException {
        GetRecoveryConfigRequest request = new GetRecoveryConfigRequest();
        request.setApplicationId(applicationId);
        return getRecoveryConfig(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public UpdateRecoveryConfigResponse updateRecoveryConfig(UpdateRecoveryConfigRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/recovery/config/update", request, queryParams, httpHeaders, UpdateRecoveryConfigResponse.class);
    }

    @Override
    public UpdateRecoveryConfigResponse updateRecoveryConfig(Long applicationId, Boolean activationRecoveryEnabled, Boolean recoveryPostcardEnabled, Boolean allowMultipleRecoveryCodes, String remoteRecoveryPublicKeyBase64) throws PowerAuthClientException {
        UpdateRecoveryConfigRequest request = new UpdateRecoveryConfigRequest();
        request.setApplicationId(applicationId);
        request.setActivationRecoveryEnabled(activationRecoveryEnabled);
        request.setRecoveryPostcardEnabled(recoveryPostcardEnabled);
        request.setAllowMultipleRecoveryCodes(allowMultipleRecoveryCodes);
        request.setRemotePostcardPublicKey(remoteRecoveryPublicKeyBase64);
        return updateRecoveryConfig(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public ListActivationFlagsResponse listActivationFlags(ListActivationFlagsRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/flags/list", request, queryParams, httpHeaders, ListActivationFlagsResponse.class);
    }

    @Override
    public ListActivationFlagsResponse listActivationFlags(String activationId) throws PowerAuthClientException {
        ListActivationFlagsRequest request = new ListActivationFlagsRequest();
        request.setActivationId(activationId);
        return listActivationFlags(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public AddActivationFlagsResponse addActivationFlags(AddActivationFlagsRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/flags/create", request, queryParams, httpHeaders, AddActivationFlagsResponse.class);
    }

    @Override
    public AddActivationFlagsResponse addActivationFlags(String activationId, List<String> activationFlags) throws PowerAuthClientException {
        AddActivationFlagsRequest request = new AddActivationFlagsRequest();
        request.setActivationId(activationId);
        request.getActivationFlags().addAll(activationFlags);
        return addActivationFlags(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public UpdateActivationFlagsResponse updateActivationFlags(UpdateActivationFlagsRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/flags/update", request, queryParams, httpHeaders, UpdateActivationFlagsResponse.class);
    }

    @Override
    public UpdateActivationFlagsResponse updateActivationFlags(String activationId, List<String> activationFlags) throws PowerAuthClientException {
        UpdateActivationFlagsRequest request = new UpdateActivationFlagsRequest();
        request.setActivationId(activationId);
        request.getActivationFlags().addAll(activationFlags);
        return updateActivationFlags(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public RemoveActivationFlagsResponse removeActivationFlags(RemoveActivationFlagsRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/activation/flags/remove", request, queryParams, httpHeaders, RemoveActivationFlagsResponse.class);
    }

    @Override
    public RemoveActivationFlagsResponse removeActivationFlags(String activationId, List<String> activationFlags) throws PowerAuthClientException {
        RemoveActivationFlagsRequest request = new RemoveActivationFlagsRequest();
        request.setActivationId(activationId);
        request.getActivationFlags().addAll(activationFlags);
        return removeActivationFlags(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public ListApplicationRolesResponse listApplicationRoles(ListApplicationRolesRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/roles/list", request, queryParams, httpHeaders, ListApplicationRolesResponse.class);
    }

    @Override
    public ListApplicationRolesResponse listApplicationRoles(Long applicationId) throws PowerAuthClientException {
        ListApplicationRolesRequest request = new ListApplicationRolesRequest();
        request.setApplicationId(applicationId);
        return listApplicationRoles(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public AddApplicationRolesResponse addApplicationRoles(AddApplicationRolesRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/roles/create", request, queryParams, httpHeaders, AddApplicationRolesResponse.class);
    }

    @Override
    public AddApplicationRolesResponse addApplicationRoles(Long applicationId, List<String> applicationRoles) throws PowerAuthClientException {
        AddApplicationRolesRequest request = new AddApplicationRolesRequest();
        request.setApplicationId(applicationId);
        request.getApplicationRoles().addAll(applicationRoles);
        return addApplicationRoles(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public UpdateApplicationRolesResponse updateApplicationRoles(UpdateApplicationRolesRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/roles/update", request, queryParams, httpHeaders, UpdateApplicationRolesResponse.class);
    }

    @Override
    public UpdateApplicationRolesResponse updateApplicationRoles(Long applicationId, List<String> applicationRoles) throws PowerAuthClientException {
        UpdateApplicationRolesRequest request = new UpdateApplicationRolesRequest();
        request.setApplicationId(applicationId);
        request.getApplicationRoles().addAll(applicationRoles);
        return updateApplicationRoles(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public RemoveApplicationRolesResponse removeApplicationRoles(RemoveApplicationRolesRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/application/roles/remove", request, queryParams, httpHeaders, RemoveApplicationRolesResponse.class);
    }

    @Override
    public RemoveApplicationRolesResponse removeApplicationRoles(Long applicationId, List<String> applicationRoles) throws PowerAuthClientException {
        RemoveApplicationRolesRequest request = new RemoveApplicationRolesRequest();
        request.setApplicationId(applicationId);
        request.getApplicationRoles().addAll(applicationRoles);
        return removeApplicationRoles(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
    }

    @Override
    public OperationDetailResponse createOperation(OperationCreateRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/operation/create", request, queryParams, httpHeaders, OperationDetailResponse.class);
    }

    @Override
    public OperationDetailResponse operationDetail(OperationDetailRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/operation/detail", request, queryParams, httpHeaders, OperationDetailResponse.class);
    }

    @Override
    public OperationListResponse operationList(OperationListForUserRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/operation/list", request, queryParams, httpHeaders, OperationListResponse.class);
    }

    @Override
    public OperationListResponse operationPendingList(OperationListForUserRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/operation/list/pending", request, queryParams, httpHeaders, OperationListResponse.class);
    }

    @Override
    public OperationDetailResponse operationCancel(OperationCancelRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/operation/cancel", request, queryParams, httpHeaders, OperationDetailResponse.class);
    }

    @Override
    public OperationUserActionResponse operationApprove(OperationApproveRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/operation/approve", request, queryParams, httpHeaders, OperationUserActionResponse.class);
    }

    @Override
    public OperationUserActionResponse failApprovalOperation(OperationFailApprovalRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/operation/approve/fail", request, queryParams, httpHeaders, OperationUserActionResponse.class);
    }

    @Override
    public OperationUserActionResponse operationReject(OperationRejectRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/operation/reject", request, queryParams, httpHeaders, OperationUserActionResponse.class);
    }

    @Override
    public OperationTemplateListResponse operationTemplateList() throws PowerAuthClientException {
        return callV3RestApi("/operation/template/list", new Object(), EMPTY_MULTI_MAP, EMPTY_MULTI_MAP, OperationTemplateListResponse.class);
    }

    @Override
    public OperationTemplateDetailResponse operationTemplateDetail(OperationTemplateDetailRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/operation/template/detail", request, queryParams, httpHeaders, OperationTemplateDetailResponse.class);
    }

    @Override
    public OperationTemplateDetailResponse createOperationTemplate(OperationTemplateCreateRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/operation/template/create", request, queryParams, httpHeaders, OperationTemplateDetailResponse.class);
    }

    @Override
    public OperationTemplateDetailResponse updateOperationTemplate(OperationTemplateUpdateRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/operation/template/update", request, queryParams, httpHeaders, OperationTemplateDetailResponse.class);
    }

    @Override
    public Response removeOperationTemplate(OperationTemplateDeleteRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
        return callV3RestApi("/operation/template/remove", request, queryParams, httpHeaders, Response.class);
    }

    @Override
    public PowerAuthClientV2 v2() {
        return new PowerAuthServiceClientV2();
    }

    /**
     * Client with PowerAuth version 2.0 methods. This client will be deprecated in future release.
     */
    public class PowerAuthServiceClientV2 implements PowerAuthClientV2 {

        private static final String PA_REST_V2_PREFIX = "/v2";

        /**
         * Call the PowerAuth v2 API.
         *
         * @param path Path of the endpoint.
         * @param request Request object.
         * @param queryParams HTTP query parameters.
         * @param httpHeaders HTTP headers.
         * @param responseType Response type.
         * @return Response.
         */
        private <T> T callV2RestApi(String path, Object request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders, Class<T> responseType) throws PowerAuthClientException {
            ObjectRequest<?> objectRequest = new ObjectRequest<>(request);
            try {
                ObjectResponse<T> objectResponse = restClient.postObject(PA_REST_V2_PREFIX + path, objectRequest, responseType);
                return objectResponse.getResponseObject();
            } catch (RestClientException ex) {
                if (ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
                    // Error handling for PowerAuth errors
                    handleBadRequestError(ex);
                }
                // Error handling for generic HTTP errors
                throw new PowerAuthClientException(ex.getMessage(), ex);
            }
        }

        @Override
        public com.wultra.security.powerauth.client.v2.PrepareActivationResponse prepareActivation(com.wultra.security.powerauth.client.v2.PrepareActivationRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
            return callV2RestApi("/activation/prepare", request, queryParams, httpHeaders, com.wultra.security.powerauth.client.v2.PrepareActivationResponse.class);
        }

        @Override
        public com.wultra.security.powerauth.client.v2.PrepareActivationResponse prepareActivation(String activationIdShort, String activationName, String activationNonce, String ephemeralPublicKey, String cDevicePublicKey, String extras, String applicationKey, String applicationSignature) throws PowerAuthClientException {
            com.wultra.security.powerauth.client.v2.PrepareActivationRequest request = new com.wultra.security.powerauth.client.v2.PrepareActivationRequest();
            request.setActivationIdShort(activationIdShort);
            request.setActivationName(activationName);
            request.setActivationNonce(activationNonce);
            request.setEphemeralPublicKey(ephemeralPublicKey);
            request.setEncryptedDevicePublicKey(cDevicePublicKey);
            request.setExtras(extras);
            request.setApplicationKey(applicationKey);
            request.setApplicationSignature(applicationSignature);
            return this.prepareActivation(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
        }

        @Override
        public com.wultra.security.powerauth.client.v2.CreateActivationResponse createActivation(com.wultra.security.powerauth.client.v2.CreateActivationRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
            return callV2RestApi("/activation/create", request, queryParams, httpHeaders, com.wultra.security.powerauth.client.v2.CreateActivationResponse.class);
        }

        @Override
        public com.wultra.security.powerauth.client.v2.CreateActivationResponse createActivation(String applicationKey, String userId, String identity, String activationName, String activationNonce, String ephemeralPublicKey, String cDevicePublicKey, String extras, String applicationSignature) throws PowerAuthClientException {
            return this.createActivation(
                    applicationKey,
                    userId,
                    null,
                    null,
                    identity,
                    "00000-00000",
                    activationName,
                    activationNonce,
                    ephemeralPublicKey,
                    cDevicePublicKey,
                    extras,
                    applicationSignature
            );
        }

        @Override
        public com.wultra.security.powerauth.client.v2.CreateActivationResponse createActivation(String applicationKey, String userId, Long maxFailureCount, Date timestampActivationExpire, String identity, String activationOtp, String activationName, String activationNonce, String ephemeralPublicKey, String cDevicePublicKey, String extras, String applicationSignature) throws PowerAuthClientException {
            com.wultra.security.powerauth.client.v2.CreateActivationRequest request = new com.wultra.security.powerauth.client.v2.CreateActivationRequest();
            request.setApplicationKey(applicationKey);
            request.setUserId(userId);
            if (maxFailureCount != null) {
                request.setMaxFailureCount(maxFailureCount);
            }
            if (timestampActivationExpire != null) {
                request.setTimestampActivationExpire(calendarWithDate(timestampActivationExpire));
            }
            request.setIdentity(identity);
            request.setActivationOtp(activationOtp);
            request.setActivationName(activationName);
            request.setActivationNonce(activationNonce);
            request.setEphemeralPublicKey(ephemeralPublicKey);
            request.setEncryptedDevicePublicKey(cDevicePublicKey);
            request.setExtras(extras);
            request.setApplicationSignature(applicationSignature);
            return this.createActivation(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
        }

        @Override
        public com.wultra.security.powerauth.client.v2.VaultUnlockResponse unlockVault(com.wultra.security.powerauth.client.v2.VaultUnlockRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
            return callV2RestApi("/vault/unlock", request, queryParams, httpHeaders, com.wultra.security.powerauth.client.v2.VaultUnlockResponse.class);
        }

        @Override
        public com.wultra.security.powerauth.client.v2.VaultUnlockResponse unlockVault(String activationId, String applicationKey, String data, String signature, com.wultra.security.powerauth.client.v2.SignatureType signatureType, String reason) throws PowerAuthClientException {
            com.wultra.security.powerauth.client.v2.VaultUnlockRequest request = new com.wultra.security.powerauth.client.v2.VaultUnlockRequest();
            request.setActivationId(activationId);
            request.setApplicationKey(applicationKey);
            request.setData(data);
            request.setSignature(signature);
            request.setSignatureType(signatureType);
            request.setReason(reason);
            return this.unlockVault(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
        }

        @Override
        public com.wultra.security.powerauth.client.v2.GetPersonalizedEncryptionKeyResponse generatePersonalizedE2EEncryptionKey(com.wultra.security.powerauth.client.v2.GetPersonalizedEncryptionKeyRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
            return callV2RestApi("/activation/encryption/key/create", request, queryParams, httpHeaders, com.wultra.security.powerauth.client.v2.GetPersonalizedEncryptionKeyResponse.class);
        }

        @Override
        public com.wultra.security.powerauth.client.v2.GetPersonalizedEncryptionKeyResponse generatePersonalizedE2EEncryptionKey(String activationId, String sessionIndex) throws PowerAuthClientException {
            com.wultra.security.powerauth.client.v2.GetPersonalizedEncryptionKeyRequest request = new com.wultra.security.powerauth.client.v2.GetPersonalizedEncryptionKeyRequest();
            request.setActivationId(activationId);
            request.setSessionIndex(sessionIndex);
            return this.generatePersonalizedE2EEncryptionKey(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
        }

        @Override
        public com.wultra.security.powerauth.client.v2.GetNonPersonalizedEncryptionKeyResponse generateNonPersonalizedE2EEncryptionKey(com.wultra.security.powerauth.client.v2.GetNonPersonalizedEncryptionKeyRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
            return callV2RestApi("/application/encryption/key/create", request, queryParams, httpHeaders, com.wultra.security.powerauth.client.v2.GetNonPersonalizedEncryptionKeyResponse.class);
        }

        @Override
        public com.wultra.security.powerauth.client.v2.GetNonPersonalizedEncryptionKeyResponse generateNonPersonalizedE2EEncryptionKey(String applicationKey, String ephemeralPublicKeyBase64, String sessionIndex) throws PowerAuthClientException {
            com.wultra.security.powerauth.client.v2.GetNonPersonalizedEncryptionKeyRequest request = new com.wultra.security.powerauth.client.v2.GetNonPersonalizedEncryptionKeyRequest();
            request.setApplicationKey(applicationKey);
            request.setEphemeralPublicKey(ephemeralPublicKeyBase64);
            request.setSessionIndex(sessionIndex);
            return this.generateNonPersonalizedE2EEncryptionKey(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
        }

        @Override
        public com.wultra.security.powerauth.client.v2.CreateTokenResponse createToken(com.wultra.security.powerauth.client.v2.CreateTokenRequest request, MultiValueMap<String, String> queryParams, MultiValueMap<String, String> httpHeaders) throws PowerAuthClientException {
            return callV2RestApi("/token/create", request, queryParams, httpHeaders, com.wultra.security.powerauth.client.v2.CreateTokenResponse.class);
        }

        @Override
        public com.wultra.security.powerauth.client.v2.CreateTokenResponse createToken(String activationId, String ephemeralPublicKey, com.wultra.security.powerauth.client.v2.SignatureType signatureType) throws PowerAuthClientException {
            com.wultra.security.powerauth.client.v2.CreateTokenRequest request = new com.wultra.security.powerauth.client.v2.CreateTokenRequest();
            request.setActivationId(activationId);
            request.setEphemeralPublicKey(ephemeralPublicKey);
            request.setSignatureType(signatureType);
            return createToken(request, EMPTY_MULTI_MAP, EMPTY_MULTI_MAP);
        }
    }

}
