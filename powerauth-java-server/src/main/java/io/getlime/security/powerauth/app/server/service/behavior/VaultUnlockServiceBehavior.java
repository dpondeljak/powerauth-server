/*
 * PowerAuth Server and related software components
 * Copyright (C) 2017 Lime - HighTech Solutions s.r.o.
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

package io.getlime.security.powerauth.app.server.service.behavior;

import com.google.common.io.BaseEncoding;
import io.getlime.security.powerauth.VaultUnlockResponse;
import io.getlime.security.powerauth.app.server.repository.ActivationRepository;
import io.getlime.security.powerauth.app.server.repository.model.ActivationStatus;
import io.getlime.security.powerauth.app.server.repository.model.entity.ActivationRecordEntity;
import io.getlime.security.powerauth.app.server.service.util.ModelUtil;
import io.getlime.security.powerauth.crypto.server.vault.PowerAuthServerVault;
import io.getlime.security.powerauth.provider.CryptoProviderUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;

/**
 * Behavior class implementing the vault unlock related processes. The class separates the
 * logic from the main service class.
 *
 * @author Petr Dvorak
 */
@Component
public class VaultUnlockServiceBehavior {

    private ActivationRepository powerAuthRepository;

    @Autowired
    public VaultUnlockServiceBehavior(ActivationRepository powerAuthRepository) {
        this.powerAuthRepository = powerAuthRepository;
    }

    private final PowerAuthServerVault powerAuthServerVault = new PowerAuthServerVault();

    /**
     * Method to retrieve the vault unlock key. Before calling this method, it is assumed that
     * client application performs signature validation - this method should not be called unauthenticated.
     * To indicate the signature validation result, 'isSignatureValid' boolean is passed as one of the
     * method parameters.
     *
     * @param activationId           Activation ID.
     * @param isSignatureValid       Information about validity of the signature.
     * @param keyConversionUtilities Key conversion utilities.
     * @return Vault unlock response with a properly encrypted vault unlock key.
     * @throws InvalidKeySpecException In case invalid key is provided.
     * @throws InvalidKeyException     In case invalid key is provided.
     */
    public VaultUnlockResponse unlockVault(String activationId, boolean isSignatureValid, CryptoProviderUtil keyConversionUtilities) throws InvalidKeySpecException, InvalidKeyException {
        // Find related activation record
        ActivationRecordEntity activation = powerAuthRepository.findFirstByActivationId(activationId);

        if (activation != null && activation.getActivationStatus() == ActivationStatus.ACTIVE) {

            // Check if the signature is valid
            if (isSignatureValid) {

                // Get the server private and device public keys
                byte[] serverPrivateKeyBytes = BaseEncoding.base64().decode(activation.getServerPrivateKeyBase64());
                byte[] devicePublicKeyBytes = BaseEncoding.base64().decode(activation.getDevicePublicKeyBase64());
                PrivateKey serverPrivateKey = keyConversionUtilities.convertBytesToPrivateKey(serverPrivateKeyBytes);
                PublicKey devicePublicKey = keyConversionUtilities.convertBytesToPublicKey(devicePublicKeyBytes);

                // Get encrypted vault unlock key and increment the counter
                Long counter = activation.getCounter();
                byte[] cKeyBytes = powerAuthServerVault.encryptVaultEncryptionKey(serverPrivateKey, devicePublicKey, counter);
                activation.setCounter(counter + 1);
                powerAuthRepository.save(activation);

                // return the data
                VaultUnlockResponse response = new VaultUnlockResponse();
                response.setActivationId(activationId);
                response.setActivationStatus(ModelUtil.toServiceStatus(ActivationStatus.ACTIVE));
                response.setRemainingAttempts(BigInteger.valueOf(activation.getMaxFailedAttempts()));
                response.setSignatureValid(true);
                response.setUserId(activation.getUserId());
                response.setEncryptedVaultEncryptionKey(BaseEncoding.base64().encode(cKeyBytes));

                return response;

            } else {

                // Even if the signature is not valid, increment the counter
                Long counter = activation.getCounter();
                activation.setCounter(counter + 1);
                powerAuthRepository.save(activation);

                // return the data
                VaultUnlockResponse response = new VaultUnlockResponse();
                response.setActivationId(activationId);
                response.setActivationStatus(ModelUtil.toServiceStatus(activation.getActivationStatus()));
                response.setRemainingAttempts(BigInteger.valueOf(activation.getMaxFailedAttempts() - activation.getFailedAttempts()));
                response.setSignatureValid(false);
                response.setUserId(activation.getUserId());
                response.setEncryptedVaultEncryptionKey(null);

                return response;
            }

        } else {

            // return the data
            VaultUnlockResponse response = new VaultUnlockResponse();
            response.setActivationId(activationId);
            response.setActivationStatus(ModelUtil.toServiceStatus(ActivationStatus.REMOVED));
            response.setRemainingAttempts(BigInteger.valueOf(0));
            response.setSignatureValid(false);
            response.setUserId("UNKNOWN");
            response.setEncryptedVaultEncryptionKey(null);

            return response;
        }
    }

}
