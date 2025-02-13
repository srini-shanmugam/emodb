package com.bazaarvoice.emodb.uac.client2;

import com.bazaarvoice.emodb.auth.apikey.ApiKeyRequest;
import com.bazaarvoice.emodb.client2.EmoClient;
import com.bazaarvoice.emodb.client2.EmoClientException;
import com.bazaarvoice.emodb.client2.EmoResource;
import com.bazaarvoice.emodb.client2.EmoResponse;
import com.bazaarvoice.emodb.client2.uri.EmoUriBuilder;
import com.bazaarvoice.emodb.common.api.ServiceUnavailableException;
import com.bazaarvoice.emodb.common.api.UnauthorizedException;
import com.bazaarvoice.emodb.common.json.JsonHelper;
import com.bazaarvoice.emodb.common.json.JsonStreamProcessingException;
import com.bazaarvoice.emodb.uac.api.AuthUserAccessControl;
import com.bazaarvoice.emodb.uac.api.CreateEmoApiKeyRequest;
import com.bazaarvoice.emodb.uac.api.CreateEmoApiKeyResponse;
import com.bazaarvoice.emodb.uac.api.CreateEmoRoleRequest;
import com.bazaarvoice.emodb.uac.api.EmoApiKey;
import com.bazaarvoice.emodb.uac.api.EmoApiKeyExistsException;
import com.bazaarvoice.emodb.uac.api.EmoApiKeyNotFoundException;
import com.bazaarvoice.emodb.uac.api.EmoRole;
import com.bazaarvoice.emodb.uac.api.EmoRoleExistsException;
import com.bazaarvoice.emodb.uac.api.EmoRoleKey;
import com.bazaarvoice.emodb.uac.api.EmoRoleNotFoundException;
import com.bazaarvoice.emodb.uac.api.InsufficientRolePermissionException;
import com.bazaarvoice.emodb.uac.api.InvalidEmoPermissionException;
import com.bazaarvoice.emodb.uac.api.MigrateEmoApiKeyRequest;
import com.bazaarvoice.emodb.uac.api.UpdateEmoApiKeyRequest;
import com.bazaarvoice.emodb.uac.api.UpdateEmoRoleRequest;
import com.fasterxml.jackson.core.type.TypeReference;


import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;


import static java.util.Objects.requireNonNull;

/**
 * Client implementation of {@link AuthUserAccessControl} which makes REST calls to the Emo service.
 */
public class UserAccessControlClient implements AuthUserAccessControl {

    private static final MediaType APPLICATION_X_CREATE_ROLE_TYPE = new MediaType("application", "x.json-create-role");
    private static final MediaType APPLICATION_X_UPDATE_ROLE_TYPE = new MediaType("application", "x.json-update-role");
    private static final MediaType APPLICATION_X_CREATE_API_KEY_TYPE = new MediaType("application", "x.json-create-api-key");
    private static final MediaType APPLICATION_X_UPDATE_API_KEY_TYPE = new MediaType("application", "x.json-update-api-key");

    private final EmoClient _client;
    private final UriBuilder _uac;
    
    public UserAccessControlClient(URI endPoint, EmoClient client) {
        _client = requireNonNull(client, "client");
        _uac = EmoUriBuilder.fromUri(endPoint);
    }

    @Override
    public Iterator<EmoRole> getAllRoles(String apiKey) {
        try {
            URI uri = _uac.clone()
                    .segment("role")
                    .build();
            return _client.resource(uri)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                    .get(new TypeReference<Iterator<EmoRole>>(){});
        } catch (EmoClientException e) {
            throw convertException(e);
        }
    }

    @Override
    public Iterator<EmoRole> getAllRolesInGroup(String apiKey, String group) {
        try {
            URI uri = _uac.clone()
                    .segment("role")
                    .segment(Optional.ofNullable(group).orElse(EmoRoleKey.NO_GROUP))
                    .build();
            return _client.resource(uri)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                    .get(new TypeReference<Iterator<EmoRole>>(){});
        } catch (EmoClientException e) {
            throw convertException(e);
        }
    }

    @Override
    public EmoRole getRole(String apiKey, EmoRoleKey roleKey) {
        requireNonNull(roleKey, "roleKey");
        URI uri = _uac.clone()
                .segment("role")
                .segment(roleKey.getGroup())
                .segment(roleKey.getId())
                .build();
        EmoResponse response = _client.resource(uri)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                .get(EmoResponse.class);

        if (response.getStatus() == Response.Status.OK.getStatusCode()) {
            return response.getEntity(EmoRole.class);
        } else if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
            return null;
        }

        throw convertException(new EmoClientException(response));
    }

    @Override
    public void createRole(String apiKey, CreateEmoRoleRequest request)
            throws EmoRoleExistsException {
        requireNonNull(request, "request");
        EmoRoleKey roleKey = requireNonNull(request.getRoleKey(), "roleKey");
        try {
            URI uri = _uac.clone()
                    .segment("role")
                    .segment(roleKey.getGroup())
                    .segment(roleKey.getId())
                    .build();
            _client.resource(uri)
                    .type(APPLICATION_X_CREATE_ROLE_TYPE)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                    .post(JsonHelper.asJson(request));
        } catch (EmoClientException e) {
            throw convertException(e);
        }
    }

    @Override
    public void updateRole(String apiKey, UpdateEmoRoleRequest request)
            throws EmoRoleNotFoundException {
        requireNonNull(request, "request");
        EmoRoleKey roleKey = requireNonNull(request.getRoleKey(), "roleKey");
        try {
            URI uri = _uac.clone()
                    .segment("role")
                    .segment(roleKey.getGroup())
                    .segment(roleKey.getId())
                    .build();
            _client.resource(uri)
                    .type(APPLICATION_X_UPDATE_ROLE_TYPE)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                    .put(JsonHelper.asJson(request));
        } catch (EmoClientException e) {
            throw convertException(e);
        }
    }

    @Override
    public void deleteRole(String apiKey, EmoRoleKey roleKey)
            throws EmoRoleNotFoundException {
        requireNonNull(roleKey, "roleKey");
        try {
            URI uri = _uac.clone()
                    .segment("role")
                    .segment(roleKey.getGroup())
                    .segment(roleKey.getId())
                    .build();
            _client.resource(uri)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                    .delete();
        } catch (EmoClientException e) {
            throw convertException(e);
        }
    }

    @Override
    public boolean checkRoleHasPermission(String apiKey, EmoRoleKey roleKey, String permission)
            throws EmoRoleNotFoundException {
        requireNonNull(roleKey, "roleKey");
        requireNonNull(permission, "permission");
        try {
            URI uri = _uac.clone()
                    .segment("role")
                    .segment(roleKey.getGroup())
                    .segment(roleKey.getId())
                    .segment("permitted")
                    .queryParam("permission", permission)
                    .build();
            return _client.resource(uri)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                    .get(Boolean.class);
        } catch (EmoClientException e) {
            throw convertException(e);
        }
    }

    @Override
    public EmoApiKey getApiKey(String apiKey, String id) {
        requireNonNull(id, "id");
        URI uri = _uac.clone()
                .segment("api-key")
                .segment(id)
                .build();
        EmoResponse response = _client.resource(uri)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                .get(EmoResponse.class);
        return getApiKeyFromResponse(response);
    }

    @Override
    public EmoApiKey getApiKeyByKey(String apiKey, String key) {
        requireNonNull(key, "key");
        URI uri = _uac.clone()
                .segment("api-key")
                .segment("_key")
                .segment(key)
                .build();
        EmoResponse response = _client.resource(uri)
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                .get(EmoResponse.class);
        return getApiKeyFromResponse(response);
    }

    private EmoApiKey getApiKeyFromResponse(EmoResponse response) {
        if (response.getStatus() == Response.Status.OK.getStatusCode()) {
            return response.getEntity(EmoApiKey.class);
        } else if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
            return null;
        }

        throw convertException(new EmoClientException(response));
    }

    @Override
    public CreateEmoApiKeyResponse createApiKey(String apiKey, CreateEmoApiKeyRequest request)
            throws EmoApiKeyNotFoundException {
        requireNonNull(request, "request");
        if(isBlankString(request.getOwner())){
            throw new IllegalArgumentException("Non-empty owner is required");
        }
        try {
            URI uri = _uac.clone()
                    .segment("api-key")
                    .build();
            EmoResource resource = _client.resource(uri);

            for (Map.Entry<String, String> customQueryParam : request.getCustomRequestParameters().entries()) {
                resource = resource.queryParam(customQueryParam.getKey(), customQueryParam.getValue());
            }

            return resource
                    .type(APPLICATION_X_CREATE_API_KEY_TYPE)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                    .post(CreateEmoApiKeyResponse.class, JsonHelper.asJson(request));

        } catch (EmoClientException e) {
            throw convertException(e);
        }
    }

    @Override
    public void updateApiKey(String apiKey, UpdateEmoApiKeyRequest request)
            throws EmoApiKeyNotFoundException {
        requireNonNull(request, "request");
        String id = requireNonNull(request.getId(), "id");
        requireNonNull(request.getOwner(), "owner is required");
        if(isBlankString(request.getOwner()) || !request.isOwnerPresent()){
            throw new IllegalArgumentException("Non-empty owner is required");
        }

        try {
            URI uri = _uac.clone()
                    .segment("api-key")
                    .segment(id)
                    .build();
            _client.resource(uri)
                    .type(APPLICATION_X_UPDATE_API_KEY_TYPE)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                    .put(JsonHelper.asJson(request));
        } catch (EmoClientException e) {
            throw convertException(e);
        }
    }

    @Override
    public String migrateApiKey(String apiKey, String id)
            throws EmoApiKeyNotFoundException {
        return migrateApiKey(apiKey, new MigrateEmoApiKeyRequest(id));
    }

    @Override
    public String migrateApiKey(String apiKey, MigrateEmoApiKeyRequest request) {
        requireNonNull(request, "request");
        String id = requireNonNull(request.getId(), "id");
        try {
            URI uri = _uac.clone()
                    .segment("api-key")
                    .segment(id)
                    .segment("migrate")
                    .build();

            EmoResource resource = _client.resource(uri);

            for (Map.Entry<String, String> customQueryParam : request.getCustomRequestParameters().entries()) {
                resource = resource.queryParam(customQueryParam.getKey(), customQueryParam.getValue());
            }

            CreateEmoApiKeyResponse response = resource
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                    .post(CreateEmoApiKeyResponse.class, null);
            
            return response.getKey();
        } catch (EmoClientException e) {
            throw convertException(e);
        }
    }

    @Override
    public void deleteApiKey(String apiKey, String id)
            throws EmoApiKeyNotFoundException {
        requireNonNull(id, "id");
        try {
            URI uri = _uac.clone()
                    .segment("api-key")
                    .segment(id)
                    .build();
            _client.resource(uri)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                    .delete();
        } catch (EmoClientException e) {
            throw convertException(e);
        }
    }

    @Override
    public boolean checkApiKeyHasPermission(String apiKey, String id, String permission)
            throws EmoApiKeyNotFoundException {
        requireNonNull(id, "id");
        requireNonNull(permission, "permission");
        try {
            URI uri = _uac.clone()
                    .segment("api-key")
                    .segment(id)
                    .segment("permitted")
                    .queryParam("permission", permission)
                    .build();
            return _client.resource(uri)
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(ApiKeyRequest.AUTHENTICATION_HEADER, apiKey)
                    .get(Boolean.class);
        } catch (EmoClientException e) {
            throw convertException(e);
        }
    }

    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    private RuntimeException convertException(EmoClientException e) {
        EmoResponse response = e.getResponse();
        String exceptionType = response.getFirstHeader("X-BV-Exception");

        if (response.getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            if (InvalidEmoPermissionException.class.getName().equals(exceptionType)) {
                if (response.hasEntity()) {
                    return (RuntimeException) response.getEntity(InvalidEmoPermissionException.class).initCause(e);
                } else {
                    return (RuntimeException) new InvalidEmoPermissionException().initCause(e);
                }
            } else if (IllegalArgumentException.class.getName().equals(exceptionType)) {
                return new IllegalArgumentException(response.getEntity(String.class), e);
            } else if (JsonStreamProcessingException.class.getName().equals(exceptionType)) {
                return new JsonStreamProcessingException(response.getEntity(String.class));
            }

        } else if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
            if (EmoApiKeyExistsException.class.getName().equals(exceptionType)) {
                if (response.hasEntity()) {
                    return (RuntimeException) response.getEntity(EmoApiKeyExistsException.class).initCause(e);
                } else {
                    return (RuntimeException) new EmoApiKeyExistsException().initCause(e);
                }
            } else if (EmoRoleExistsException.class.getName().equals(exceptionType)) {
                if (response.hasEntity()) {
                    return (RuntimeException) response.getEntity(EmoRoleExistsException.class).initCause(e);
                } else {
                    return (RuntimeException) new EmoRoleExistsException().initCause(e);
                }
            }

        } else if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
            if (EmoApiKeyNotFoundException.class.getName().equals(exceptionType)) {
                if (response.hasEntity()) {
                    return (RuntimeException) response.getEntity(EmoApiKeyNotFoundException.class).initCause(e);
                } else {
                    return (RuntimeException) new EmoApiKeyNotFoundException().initCause(e);
                }
            } else if (EmoRoleNotFoundException.class.getName().equals(exceptionType)) {
                if (response.hasEntity()) {
                    return (RuntimeException) response.getEntity(EmoRoleNotFoundException.class).initCause(e);
                } else {
                    return (RuntimeException) new EmoRoleNotFoundException().initCause(e);
                }
            }

        } else if (response.getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
            if (UnauthorizedException.class.getName().equals(exceptionType)) {
                if (response.hasEntity()) {
                    return (RuntimeException) response.getEntity(UnauthorizedException.class).initCause(e);
                } else {
                    return (RuntimeException) new UnauthorizedException().initCause(e);
                }
            } else if (InsufficientRolePermissionException.class.getName().equals(exceptionType)) {
                if (response.hasEntity()) {
                    return (RuntimeException) response.getEntity(InsufficientRolePermissionException.class).initCause(e);
                } else {
                    return (RuntimeException) new InsufficientRolePermissionException().initCause(e);
                }
            }

        } else if (response.getStatus() == Response.Status.SERVICE_UNAVAILABLE.getStatusCode() &&
                ServiceUnavailableException.class.getName().equals(exceptionType)) {
            if (response.hasEntity()) {
                return (RuntimeException) response.getEntity(ServiceUnavailableException.class).initCause(e);
            } else {
                return (RuntimeException) new ServiceUnavailableException().initCause(e);
            }
        }

        return e;
    }

    private boolean isBlankString(String string) {
        return string == null || string.trim().isEmpty();
    }
}
