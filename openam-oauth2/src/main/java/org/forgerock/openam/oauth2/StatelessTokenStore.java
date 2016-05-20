/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2016 ForgeRock AS.
 */

package org.forgerock.openam.oauth2;

import static org.forgerock.json.JsonValue.json;
import static org.forgerock.json.JsonValue.set;
import static org.forgerock.openam.oauth2.OAuth2Constants.Bearer.BEARER;
import static org.forgerock.openam.oauth2.OAuth2Constants.CoreTokenParams.*;
import static org.forgerock.openam.oauth2.OAuth2Constants.Custom.CLAIMS;
import static org.forgerock.openam.oauth2.OAuth2Constants.JWTTokenParams.ACR;
import static org.forgerock.openam.oauth2.OAuth2Constants.Params.EXPIRES_IN;
import static org.forgerock.openam.oauth2.OAuth2Constants.Params.GRANT_TYPE;
import static org.forgerock.openam.oauth2.OAuth2Constants.Token.OAUTH_ACCESS_TOKEN;
import static org.forgerock.openam.oauth2.OAuth2Constants.Token.OAUTH_REFRESH_TOKEN;
import static org.forgerock.openam.oauth2.OAuth2Constants.Token.OAUTH_TOKEN_TYPE;
import static org.forgerock.openam.utils.Time.currentTimeMillis;
import static org.forgerock.openam.utils.Time.newDate;

import javax.inject.Inject;
import javax.inject.Named;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.sun.identity.shared.debug.Debug;
import org.forgerock.json.JsonValue;
import org.forgerock.json.jose.builders.JwtBuilderFactory;
import org.forgerock.json.jose.builders.JwtClaimsSetBuilder;
import org.forgerock.json.jose.common.JwtReconstruction;
import org.forgerock.json.jose.exceptions.InvalidJwtException;
import org.forgerock.json.jose.jws.JwsAlgorithm;
import org.forgerock.json.jose.jws.SignedJwt;
import org.forgerock.json.jose.jws.SigningManager;
import org.forgerock.json.jose.jws.handlers.SigningHandler;
import org.forgerock.oauth2.core.AccessToken;
import org.forgerock.oauth2.core.AuthorizationCode;
import org.forgerock.oauth2.core.DeviceCode;
import org.forgerock.oauth2.core.OAuth2ProviderSettings;
import org.forgerock.oauth2.core.OAuth2ProviderSettingsFactory;
import org.forgerock.oauth2.core.OAuth2Request;
import org.forgerock.oauth2.core.OAuth2UrisFactory;
import org.forgerock.oauth2.core.RefreshToken;
import org.forgerock.oauth2.core.ResourceOwner;
import org.forgerock.oauth2.core.TokenStore;
import org.forgerock.oauth2.core.exceptions.InvalidClientException;
import org.forgerock.oauth2.core.exceptions.InvalidGrantException;
import org.forgerock.oauth2.core.exceptions.InvalidRequestException;
import org.forgerock.oauth2.core.exceptions.NotFoundException;
import org.forgerock.oauth2.core.exceptions.ServerException;
import org.forgerock.openam.core.RealmInfo;
import org.forgerock.openam.tokens.CoreTokenField;
import org.forgerock.openam.utils.RealmNormaliser;
import org.forgerock.openam.utils.StringUtils;
import org.forgerock.openidconnect.OpenIdConnectClientRegistration;
import org.forgerock.openidconnect.OpenIdConnectClientRegistrationStore;
import org.forgerock.services.context.Context;
import org.forgerock.util.encode.Base64;
import org.forgerock.util.query.QueryFilter;
import org.joda.time.Duration;

/**
 * Stateless implementation of the OAuth2 Token Store.
 */
public class StatelessTokenStore implements TokenStore {

    private final Debug logger;
    private final TokenStore statefulTokenStore;
    private final JwtBuilderFactory jwtBuilder;
    private final OAuth2ProviderSettingsFactory providerSettingsFactory;
    private final OpenIdConnectClientRegistrationStore clientRegistrationStore;
    private final RealmNormaliser realmNormaliser;
    private final OAuth2UrisFactory<RealmInfo> oAuth2UrisFactory;


    /**
     * Constructs a new StatelessTokenStore.
     *
     * @param statefulTokenStore An instance of the stateful TokenStore.
     * @param jwtBuilder An instance of the JwtBuilderFactory.
     * @param providerSettingsFactory An instance of the OAuth2ProviderSettingsFactory.
     * @param logger An instance of OAuth2AuditLogger.
     * @param clientRegistrationStore An instance of the OpenIdConnectClientRegistrationStore.
     * @param realmNormaliser An instance of the RealmNormaliser.
     * @param oAuth2UrisFactory An instance of the OAuth2UrisFactory.
     */
    @Inject
    public StatelessTokenStore(StatefulTokenStore statefulTokenStore, JwtBuilderFactory jwtBuilder,
            OAuth2ProviderSettingsFactory providerSettingsFactory, @Named(OAuth2Constants.DEBUG_LOG_NAME) Debug logger,
            OpenIdConnectClientRegistrationStore clientRegistrationStore, RealmNormaliser realmNormaliser,
            OAuth2UrisFactory<RealmInfo> oAuth2UrisFactory) {
        this.statefulTokenStore = statefulTokenStore;
        this.jwtBuilder = jwtBuilder;
        this.providerSettingsFactory = providerSettingsFactory;
        this.logger = logger;
        this.clientRegistrationStore = clientRegistrationStore;
        this.realmNormaliser = realmNormaliser;
        this.oAuth2UrisFactory = oAuth2UrisFactory;
    }

    @Override
    public AuthorizationCode createAuthorizationCode(Set<String> scope, ResourceOwner resourceOwner, String clientId,
            String redirectUri, String nonce, OAuth2Request request, String codeChallenge, String codeChallengeMethod)
            throws ServerException, NotFoundException {
        return statefulTokenStore.createAuthorizationCode(scope, resourceOwner, clientId, redirectUri, nonce, request,
                codeChallenge, codeChallengeMethod);
    }

    @Override
    public AccessToken createAccessToken(String grantType, String accessTokenType, String authorizationCode, String
            resourceOwnerId, String clientId, String redirectUri, Set<String> scope, RefreshToken refreshToken,
            String nonce, String claims, OAuth2Request request) throws ServerException, NotFoundException {
        OAuth2ProviderSettings providerSettings = providerSettingsFactory.get(request);
        OpenIdConnectClientRegistration clientRegistration = getClientRegistration(clientId, request);
        Duration currentTime = Duration.millis(currentTimeMillis());
        Duration expiresIn;
        Duration expiryTime;
        if (clientRegistration == null) {
            expiresIn = Duration.standardSeconds(providerSettings.getAccessTokenLifetime());
        } else {
            expiresIn = Duration.millis(clientRegistration.getAccessTokenLifeTime(providerSettings));
        }
        expiryTime = expiresIn.plus(currentTime);
        String realm;
        try {
            realm = realmNormaliser.normalise(request.<String>getParameter(REALM));
        } catch (org.forgerock.json.resource.NotFoundException e) {
            throw new NotFoundException(e.getMessage());
        }
        String jwtId = UUID.randomUUID().toString();
        JwtClaimsSetBuilder claimsSetBuilder = jwtBuilder.claims()
                .jti(jwtId)
                .exp(newDate(expiryTime.getMillis()))
                .aud(Collections.singletonList(clientId))
                .sub(resourceOwnerId)
                .iat(newDate(currentTime.getMillis()))
                .nbf(newDate(currentTime.getMillis()))
                .iss(oAuth2UrisFactory.get(request).getIssuer())
                .claim(SCOPE, scope)
                .claim(CLAIMS, claims)
                .claim(REALM, realm)
                .claim(TOKEN_NAME, OAUTH_ACCESS_TOKEN)
                .claim(OAUTH_TOKEN_TYPE, BEARER)
                .claim(EXPIRES_IN, expiresIn.getMillis())
                .claim(AUDIT_TRACKING_ID, UUID.randomUUID().toString())
                .claim(AUTH_GRANT_ID, refreshToken != null ? refreshToken.getAuthGrantId() : UUID.randomUUID().toString());
        JwsAlgorithm signingAlgorithm = getSigningAlgorithm(request);
        SignedJwt jwt = jwtBuilder.jws(getTokenSigningHandler(request, signingAlgorithm))
                .claims(claimsSetBuilder.build())
                .headers()
                .alg(signingAlgorithm)
                .done()
                .asJwt();
        StatelessAccessToken accessToken = new StatelessAccessToken(jwt, jwt.build());
        request.setToken(AccessToken.class, accessToken);
        return accessToken;
    }

    private OpenIdConnectClientRegistration getClientRegistration(String clientId, OAuth2Request request)
            throws ServerException, NotFoundException {
        OpenIdConnectClientRegistration clientRegistration = null;
        try {
            clientRegistration = clientRegistrationStore.get(clientId, request);
        } catch (InvalidClientException e) {
            // If the client is not registered, then returns null.
        }
        return clientRegistration;
    }

    private SigningHandler getTokenSigningHandler(OAuth2Request request, JwsAlgorithm signingAlgorithm)
            throws NotFoundException, ServerException {
        try {
            OAuth2ProviderSettings providerSettings = providerSettingsFactory.get(request);
            switch (signingAlgorithm.getAlgorithmType()) {
                case HMAC: {
                    return new SigningManager().newHmacSigningHandler(
                            Base64.decode(providerSettings.getTokenHmacSharedSecret()));
                }
                case RSA: {
                    return new SigningManager().newRsaSigningHandler(
                            providerSettings.getSigningKeyPair(signingAlgorithm).getPrivate());
                }
                case ECDSA: {
                    return new SigningManager().newEcdsaSigningHandler(
                            (ECPrivateKey) providerSettings.getSigningKeyPair(signingAlgorithm).getPrivate());
                }
                default: {
                    throw new ServerException("Unsupported Token signing algorithm");
                }
            }
        } catch (IllegalArgumentException e) {
            throw new ServerException("Invalid Token signing algorithm");
        }
    }

    private SigningHandler getTokenVerificationHandler(OAuth2ProviderSettings providerSettings, JwsAlgorithm signingAlgorithm)
            throws NotFoundException, ServerException {
        try {
            switch (signingAlgorithm.getAlgorithmType()) {
                case HMAC: {
                    return new SigningManager().newHmacSigningHandler(
                            Base64.decode(providerSettings.getTokenHmacSharedSecret()));
                }
                case RSA: {
                    return new SigningManager().newRsaSigningHandler(
                            providerSettings.getSigningKeyPair(signingAlgorithm).getPublic());
                }
                case ECDSA: {
                    return new SigningManager().newEcdsaVerificationHandler(
                            (ECPublicKey) providerSettings.getSigningKeyPair(signingAlgorithm).getPublic());
                }
                default: {
                    throw new ServerException("Unsupported Token signing algorithm");
                }
            }
        } catch (IllegalArgumentException e) {
            throw new ServerException("Invalid Token signing algorithm");
        }
    }

    private JwsAlgorithm getSigningAlgorithm(OAuth2Request request) throws ServerException, NotFoundException {
        try {
            OAuth2ProviderSettings providerSettings = providerSettingsFactory.get(request);
            JwsAlgorithm algorithm = JwsAlgorithm.valueOf(providerSettings.getTokenSigningAlgorithm().toUpperCase());
            if (!isAlgorithmSupported(request, algorithm)) {
                throw new ServerException("Unsupported Token signing algorithm");
            }
            return algorithm;
        } catch (IllegalArgumentException e) {
            throw new ServerException("Invalid Token signing algorithm");
        }
    }

    private JwsAlgorithm getSigningAlgorithm(OAuth2ProviderSettings providerSettings) throws ServerException, NotFoundException {
        try {
            JwsAlgorithm algorithm = JwsAlgorithm.valueOf(providerSettings.getTokenSigningAlgorithm().toUpperCase());
            if (!isAlgorithmSupported(providerSettings, algorithm)) {
                throw new ServerException("Unsupported Token signing algorithm");
            }
            return algorithm;
        } catch (IllegalArgumentException e) {
            throw new ServerException("Invalid Token signing algorithm");
        }
    }

    private boolean isAlgorithmSupported(OAuth2Request request, JwsAlgorithm algorithm) throws ServerException,
            NotFoundException {
        OAuth2ProviderSettings providerSettings = providerSettingsFactory.get(request);
        for (String supportedSigningAlgorithm : providerSettings.getSupportedIDTokenSigningAlgorithms()) {
            if (supportedSigningAlgorithm.toUpperCase().equals(algorithm.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isAlgorithmSupported(OAuth2ProviderSettings providerSettings, JwsAlgorithm algorithm) throws ServerException,
            NotFoundException {
        for (String supportedSigningAlgorithm : providerSettings.getSupportedIDTokenSigningAlgorithms()) {
            if (supportedSigningAlgorithm.toUpperCase().equals(algorithm.toString())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public RefreshToken createRefreshToken(String grantType, String clientId, String resourceOwnerId,
            String redirectUri, Set<String> scope, OAuth2Request request) throws ServerException, NotFoundException {
        return createRefreshToken(grantType, clientId, resourceOwnerId, redirectUri, scope, request, "");
    }

    @Override
    public RefreshToken createRefreshToken(String grantType, String clientId, String resourceOwnerId,
            String redirectUri, Set<String> scope, OAuth2Request request, String validatedClaims)
            throws ServerException, NotFoundException {
        AuthorizationCode authorizationCode = request.getToken(AuthorizationCode.class);
        String authGrantId;
        if (authorizationCode != null &&  authorizationCode.getAuthGrantId() != null  ) {
            authGrantId = authorizationCode.getAuthGrantId();
        } else {
            authGrantId = UUID.randomUUID().toString();
        }
        return createRefreshToken(grantType, clientId, resourceOwnerId, redirectUri, scope, request,
                validatedClaims, authGrantId);
    }

    @Override
    public RefreshToken createRefreshToken(String grantType, String clientId, String resourceOwnerId,
            String redirectUri, Set<String> scope, OAuth2Request request, String validatedClaims, String authGrantId)
            throws ServerException, NotFoundException {
        String realm = null;
        try {
            realm = realmNormaliser.normalise(request.<String>getParameter(REALM));
        } catch (org.forgerock.json.resource.NotFoundException e) {
            throw new NotFoundException(e.getMessage());
        }
        OpenIdConnectClientRegistration clientRegistration = getClientRegistration(clientId, request);
        OAuth2ProviderSettings providerSettings = providerSettingsFactory.get(request);
        Duration currentTime = Duration.millis(currentTimeMillis());
        Duration lifeTime;
        if (clientRegistration == null) {
            lifeTime = Duration.standardSeconds(providerSettings.getRefreshTokenLifetime());
        } else {
            lifeTime = Duration.millis(clientRegistration.getRefreshTokenLifeTime(providerSettings));
        }
        long expiryTime = lifeTime.isShorterThan(Duration.ZERO) ? -1 : lifeTime.plus(currentTime).getMillis();
        String jwtId = UUID.randomUUID().toString();
        JwtClaimsSetBuilder claimsSetBuilder = jwtBuilder.claims()
                .jti(jwtId)
                .exp(newDate(expiryTime))
                .aud(Collections.singletonList(clientId))
                .sub(resourceOwnerId)
                .iat(newDate(currentTime.getMillis()))
                .nbf(newDate(currentTime.getMillis()))
                .iss(oAuth2UrisFactory.get(request).getIssuer())
                .claim(SCOPE, scope)
                .claim(REALM, realm)
                .claim(OAUTH_TOKEN_TYPE, BEARER)
                .claim(EXPIRES_IN, lifeTime.getMillis())
                .claim(TOKEN_NAME, OAUTH_REFRESH_TOKEN)
                .claim(AUDIT_TRACKING_ID, UUID.randomUUID().toString())
                .claim(AUTH_GRANT_ID, authGrantId);


        String authModules = null;
        String acr = null;
        AuthorizationCode authorizationCode = request.getToken(AuthorizationCode.class);
        if (authorizationCode != null) {
            authModules = authorizationCode.getAuthModules();
            acr = authorizationCode.getAuthenticationContextClassReference();
        }

        RefreshToken currentRefreshToken = request.getToken(RefreshToken.class);
        if (currentRefreshToken != null) {
            authModules = currentRefreshToken.getAuthModules();
            acr = currentRefreshToken.getAuthenticationContextClassReference();
        }

        if (authModules != null) {
            claimsSetBuilder.claim(AUTH_MODULES, authModules);
        }
        if (acr != null) {
            claimsSetBuilder.claim(ACR, acr);
        }
        if (!StringUtils.isBlank(validatedClaims)) {
            claimsSetBuilder.claim(CLAIMS, validatedClaims);
        }
        JwsAlgorithm signingAlgorithm = getSigningAlgorithm(request);
        SignedJwt jwt = jwtBuilder.jws(getTokenSigningHandler(request, signingAlgorithm))
                .claims(claimsSetBuilder.build())
                .headers()
                .alg(signingAlgorithm)
                .done()
                .asJwt();

        StatelessRefreshToken refreshToken = new StatelessRefreshToken(jwt, jwt.build());
        request.setToken(RefreshToken.class, refreshToken);
        return refreshToken;
    }

    @Override
    public AuthorizationCode readAuthorizationCode(OAuth2Request request, String code) throws InvalidGrantException,
            ServerException, NotFoundException {
        return statefulTokenStore.readAuthorizationCode(request, code);
    }

    @Override
    public void updateAuthorizationCode(OAuth2Request request, AuthorizationCode authorizationCode)
            throws NotFoundException, ServerException {
        statefulTokenStore.updateAuthorizationCode(request, authorizationCode);
    }

    @Override
    public void updateAccessToken(OAuth2Request request, AccessToken accessToken) {
    }

    @Override
    public void deleteAuthorizationCode(OAuth2Request request, String authorizationCode) throws NotFoundException,
            ServerException {
        statefulTokenStore.deleteAuthorizationCode(request, authorizationCode);
    }

    @Override
    public JsonValue queryForToken(OAuth2Request request, String tokenId) throws InvalidRequestException {

        return json(set());
    }

    @Override
    public void deleteAccessToken(OAuth2Request request, String tokenId) throws ServerException {
        try {
            SignedJwt jwt = new JwtReconstruction().reconstructJwt(tokenId, SignedJwt.class);
            verifySignature(request, jwt);
            verifyTokenType(OAUTH_ACCESS_TOKEN, jwt);
            validateTokenRealm(jwt.getClaimsSet().getClaim("realm", String.class), request);
            // No-op
        } catch (InvalidJwtException | NotFoundException | InvalidGrantException e) {
            throw new ServerException("token id is not a JWT");
        }
    }

    @Override
    public void deleteRefreshToken(OAuth2Request request, String tokenId) throws InvalidRequestException,
            ServerException {
        try {
            SignedJwt jwt = new JwtReconstruction().reconstructJwt(tokenId, SignedJwt.class);
            verifySignature(request, jwt);
            verifyTokenType(OAUTH_REFRESH_TOKEN, jwt);
            validateTokenRealm(jwt.getClaimsSet().getClaim("realm", String.class), request);
            // No-op
        } catch (InvalidJwtException | NotFoundException | InvalidGrantException e) {
            throw new InvalidRequestException("token id is not a JWT");
        }
    }

    @Override
    public AccessToken readAccessToken(OAuth2Request request, String tokenId) throws ServerException,
            InvalidGrantException, NotFoundException {
        try {
            SignedJwt jwt = new JwtReconstruction().reconstructJwt(tokenId, SignedJwt.class);
            verifySignature(request, jwt);
            verifyTokenType(OAUTH_ACCESS_TOKEN, jwt);
            validateTokenRealm(jwt.getClaimsSet().getClaim("realm", String.class), request);
            StatelessAccessToken accessToken = new StatelessAccessToken(jwt, tokenId);
            request.setToken(AccessToken.class, accessToken);
            return accessToken;
        } catch (InvalidJwtException e) {
            throw new InvalidGrantException("token id is not a JWT");
        }
    }

    @Override
    public RefreshToken readRefreshToken(OAuth2Request request, String tokenId) throws ServerException,
            InvalidGrantException, NotFoundException {
        try {
            SignedJwt jwt = new JwtReconstruction().reconstructJwt(tokenId, SignedJwt.class);
            verifySignature(request, jwt);
            verifyTokenType(OAUTH_REFRESH_TOKEN, jwt);
            validateTokenRealm(jwt.getClaimsSet().getClaim("realm", String.class), request);
            return new StatelessRefreshToken(jwt, tokenId);
        } catch (InvalidJwtException e) {
            throw new InvalidGrantException("token id is not a JWT");
        }
    }

    @Override
    public DeviceCode createDeviceCode(Set<String> scope, ResourceOwner resourceOwner, String clientId, String nonce,
            String responseType, String state, String acrValues, String prompt, String uiLocales, String loginHint,
            Integer maxAge, String claims, OAuth2Request request, String codeChallenge, String codeChallengeMethod)
            throws ServerException, NotFoundException {
        return statefulTokenStore.createDeviceCode(scope, resourceOwner, clientId, nonce, responseType, state,
                acrValues, prompt, uiLocales, loginHint, maxAge, claims, request, codeChallenge, codeChallengeMethod);
    }

    @Override
    public DeviceCode readDeviceCode(String clientId, String code, OAuth2Request request) throws ServerException,
            NotFoundException, InvalidGrantException {
        return statefulTokenStore.readDeviceCode(clientId, code, request);
    }

    @Override
    public DeviceCode readDeviceCode(String userCode, OAuth2Request request) throws ServerException, NotFoundException,
            InvalidGrantException {
        return statefulTokenStore.readDeviceCode(userCode, request);
    }

    @Override
    public void updateDeviceCode(DeviceCode code, OAuth2Request request) throws ServerException, NotFoundException,
            InvalidGrantException {
        statefulTokenStore.updateDeviceCode(code, request);
    }

    @Override
    public void deleteDeviceCode(String clientId, String code, OAuth2Request request) throws ServerException,
            NotFoundException, InvalidGrantException {
        statefulTokenStore.deleteDeviceCode(clientId, code, request);
    }

    @Override
    public JsonValue queryForToken(String realm, QueryFilter<CoreTokenField> queryFilter) throws ServerException, NotFoundException {
        return json(set());
    }

    @Override
    public void delete(String realm, String tokenId) throws ServerException, NotFoundException {
        // No-op
    }

    @Override
    public JsonValue read(String tokenId) throws ServerException {

        try {
            SignedJwt jwt = new JwtReconstruction().reconstructJwt(tokenId, SignedJwt.class);
            String tokenName = jwt.getClaimsSet().getClaim(TOKEN_NAME, String.class);
            StatelessToken token;
            if(OAUTH_ACCESS_TOKEN.equals(tokenName)) {
                token = new StatelessAccessToken(jwt, tokenId);
            } else if(OAUTH_REFRESH_TOKEN.equals(tokenName)) {
                token = new StatelessRefreshToken(jwt, tokenId);
            } else {
                throw new ServerException("Unrecognised token type");
            }
            return convertToken(token);
        } catch (InvalidJwtException e) {
            throw new ServerException("token id is not a JWT");
        }
    }

    private void verifySignature(OAuth2Request request, SignedJwt jwt) throws InvalidGrantException, ServerException,
            NotFoundException {
        verifySignature(providerSettingsFactory.get(request), jwt);
    }

    private void verifySignature(OAuth2ProviderSettings providerSettings, SignedJwt jwt) throws InvalidGrantException, ServerException,
            NotFoundException {
        JwsAlgorithm signingAlgorithm = getSigningAlgorithm(providerSettings);
        if(!jwt.verify(getTokenVerificationHandler(providerSettings, signingAlgorithm))) {
            throw new InvalidGrantException();
        }
    }

    private void verifyTokenType(String requiredTokenType, SignedJwt jwt) throws InvalidGrantException {
        if (!requiredTokenType.equals(jwt.getClaimsSet().getClaim(TOKEN_NAME))) {
            throw new InvalidGrantException("Token is not an " + requiredTokenType + " token: "
                    + jwt.getClaimsSet().getJwtId());
        }
    }

    protected void validateTokenRealm(String tokenRealm, OAuth2Request request) throws InvalidGrantException,
            NotFoundException {
        try {
            final String normalisedRequestRealm = realmNormaliser.normalise(request.<String>getParameter(REALM));
            if (!tokenRealm.equals(normalisedRequestRealm) && !realmNormaliser.normalise(tokenRealm).equals(
                    normalisedRequestRealm)) {
                throw new InvalidGrantException("Grant is not valid for the requested realm");
            }
        } catch (org.forgerock.json.resource.NotFoundException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    private JsonValue convertToken(StatelessToken token) {
        Map<String, Object> map = new HashMap<>();
        map.put(USERNAME, token.getResourceOwnerId());
        map.put(CLIENT_ID, token.getClientId());
        map.put(GRANT_TYPE, token.getTokenType());
        map.put(REALM, token.getRealm());
        map.put(EXPIRE_TIME, token.getExpiryTime());
        map.put(ID, token.getJwtId());
        map.put(TOKEN_NAME, token.getTokenName());
        map.put(AUTH_GRANT_ID, token.getAuthGrantId());
        return json(map);
    }
}