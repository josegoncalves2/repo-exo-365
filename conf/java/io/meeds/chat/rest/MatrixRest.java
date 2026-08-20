/*
 * PATCH 2026-08-20: descompilado com CFR 0.152 a partir de matrix-services.jar
 * (imagem oficial Meeds/eXo 7.2.1, addon de chat). BUG REAL DA PROPRIA EXO,
 * nao configuracao -- reproduzido em qualquer instalacao nova.
 *
 * updateRoomEntity() tem dois ramos: um pra sala de ESPACO (associada a um
 * eXo Space) e um pra sala de MENSAGEM DIRETA (1:1 entre dois usuarios). O
 * ramo de espaco chama explicitamente room.setDirectChat(false) (linha ~711
 * do arquivo original). O ramo de mensagem direta preenche nome, avatar,
 * userId, identityId, dmMemberId, muted, external, enabledUser, deletedUser
 * e matrixId -- mas NUNCA chama room.setDirectChat(true). Como o campo
 * boolean "directChat" tem default false em Java, toda sala de mensagem
 * direta chega em processRooms() com isDirectChat()==false, igual uma sala
 * de espaco sem ID de espaco.
 *
 * processRooms() so' inclui uma sala no resultado se:
 *   room.isDirectChat() && chatSettings.isPrivateRoomsEnabled()
 *   OU !room.isDirectChat() && chatSettings.isSpaceRoomsEnabled() &&
 *      isChatAuthorizedForSpace(room.getSpaceId())
 * Com isDirectChat()==false incorretamente, a primeira condicao nunca bate
 * (nao e' de verdade uma sala de espaco), e a segunda tambem falha porque
 * getSpaceId() e' null pra uma DM (isChatAuthorizedForSpace(null) nao
 * autoriza). As DUAS condicoes falham SEMPRE pra toda mensagem direta --
 * a sala e' descartada silenciosamente, nao aparece nunca na lista de
 * conversas. /matrix/rest/matrix/processRooms devolve {"rooms":[]} mesmo
 * recebendo salas reais no corpo da requisicao.
 *
 * Confirmado ao vivo nesta instalacao: consulta direta na tabela MATRIX_ROOM
 * mostra 2 salas reais do usuario root (status ENABLED, SPACE_ID NULL,
 * confirmando que sao DMs) e 11 mensagens no total entre elas; a config de
 * chat (STG_SETTINGS, chave meeds.chat.settings) tem privateRoomsEnabled=
 * true, chatEnabled=true, spaceRoomsEnabled=true -- ou seja, nada estava
 * desligado por configuracao, o campo directChat e' que nunca era marcado.
 *
 * Correcao: uma linha, room.setDirectChat(true) no fim do ramo de mensagem
 * direta, espelhando o setDirectChat(false) explicito do ramo de espaco.
 * Nenhuma outra logica mudou.
 *
 * ---- cabecalho original do CFR, abaixo ----
 *
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.jsonwebtoken.Claims
 *  io.jsonwebtoken.ExpiredJwtException
 *  io.jsonwebtoken.Jws
 *  io.jsonwebtoken.Jwts
 *  io.jsonwebtoken.security.Keys
 *  io.meeds.pwa.model.PwaNotificationMessage
 *  io.swagger.v3.oas.annotations.Operation
 *  io.swagger.v3.oas.annotations.Parameter
 *  io.swagger.v3.oas.annotations.parameters.RequestBody
 *  io.swagger.v3.oas.annotations.responses.ApiResponse
 *  io.swagger.v3.oas.annotations.responses.ApiResponses
 *  io.swagger.v3.oas.annotations.tags.Tag
 *  jakarta.servlet.http.HttpServletRequest
 *  org.apache.commons.lang3.StringUtils
 *  org.exoplatform.commons.ObjectAlreadyExistsException
 *  org.exoplatform.commons.api.notification.model.UserSetting
 *  org.exoplatform.commons.api.notification.service.setting.UserSettingService
 *  org.exoplatform.commons.api.notification.service.storage.NotificationService
 *  org.exoplatform.commons.exception.ObjectNotFoundException
 *  org.exoplatform.commons.utils.CommonsUtils
 *  org.exoplatform.commons.utils.PropertyManager
 *  org.exoplatform.container.ExoContainer
 *  org.exoplatform.container.ExoContainerContext
 *  org.exoplatform.container.PortalContainer
 *  org.exoplatform.container.component.RequestLifeCycle
 *  org.exoplatform.services.log.ExoLogger
 *  org.exoplatform.services.log.Log
 *  org.exoplatform.services.resources.ResourceBundleService
 *  org.exoplatform.services.rest.resource.ResourceContainer
 *  org.exoplatform.social.core.identity.model.Identity
 *  org.exoplatform.social.core.manager.IdentityManager
 *  org.exoplatform.social.core.space.model.Space
 *  org.exoplatform.social.core.space.spi.SpaceService
 *  org.exoplatform.ws.frameworks.json.impl.JsonGeneratorImpl
 *  org.exoplatform.ws.frameworks.json.value.JsonValue
 *  org.springframework.beans.factory.annotation.Autowired
 *  org.springframework.http.HttpStatus
 *  org.springframework.http.HttpStatusCode
 *  org.springframework.http.ResponseEntity
 *  org.springframework.security.access.annotation.Secured
 *  org.springframework.web.bind.annotation.GetMapping
 *  org.springframework.web.bind.annotation.PathVariable
 *  org.springframework.web.bind.annotation.PostMapping
 *  org.springframework.web.bind.annotation.PutMapping
 *  org.springframework.web.bind.annotation.RequestBody
 *  org.springframework.web.bind.annotation.RequestMapping
 *  org.springframework.web.bind.annotation.RequestParam
 *  org.springframework.web.bind.annotation.RestController
 *  org.springframework.web.server.ResponseStatusException
 */
package io.meeds.chat.rest;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.meeds.chat.entity.RoomStatus;
import io.meeds.chat.model.Room;
import io.meeds.chat.service.ChatNotificationService;
import io.meeds.chat.service.MatrixService;
import io.meeds.chat.service.MatrixSynchronizationService;
import io.meeds.chat.service.model.ChatSettingsEntity;
import io.meeds.chat.service.model.Member;
import io.meeds.chat.service.model.Presence;
import io.meeds.chat.service.model.RoomEntity;
import io.meeds.chat.service.model.RoomList;
import io.meeds.chat.service.utils.AsyncTaskUtils;
import io.meeds.pwa.model.PwaNotificationMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Key;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.exoplatform.commons.ObjectAlreadyExistsException;
import org.exoplatform.commons.api.notification.model.UserSetting;
import org.exoplatform.commons.api.notification.service.setting.UserSettingService;
import org.exoplatform.commons.api.notification.service.storage.NotificationService;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.commons.utils.PropertyManager;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.resources.ResourceBundleService;
import org.exoplatform.services.rest.resource.ResourceContainer;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.space.model.Space;
import org.exoplatform.social.core.space.spi.SpaceService;
import org.exoplatform.ws.frameworks.json.impl.JsonGeneratorImpl;
import org.exoplatform.ws.frameworks.json.value.JsonValue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping(value={"/matrix"})
@Tag(name="/matrix", description="Manages Matrix integration")
public class MatrixRest
implements ResourceContainer {
    private static final Log LOG = ExoLogger.getLogger((String)MatrixRest.class.toString());
    @Autowired
    private SpaceService spaceService;
    @Autowired
    private MatrixService matrixService;
    @Autowired
    private MatrixSynchronizationService matrixSynchronizationService;
    @Autowired
    private IdentityManager identityManager;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private ResourceBundleService resourceBundleService;
    @Autowired
    private ChatNotificationService chatNotificationService;

    @GetMapping
    @Secured(value={"users"})
    @Operation(summary="Get the matrix room bound to the current space", method="GET", description="Get the id of the matrix room bound to the current space")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="400", description="Invalid query input"), @ApiResponse(responseCode="500", description="Internal server error")})
    public RoomEntity getMatrixRoomBySpaceId(HttpServletRequest request, @Parameter(description="The space Id") @RequestParam(name="spaceId") String spaceId) {
        if (StringUtils.isBlank((CharSequence)spaceId)) {
            LOG.error((Object)"Could not get the URL for the space, missing space ID");
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "the space Id parameter is required!");
        }
        Space space = this.spaceService.getSpaceById(spaceId);
        if (space == null) {
            LOG.error("Could not find a space with id {}", new Object[]{spaceId});
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "Can not find a space with Id = " + spaceId);
        }
        String userName = request.getRemoteUser();
        if (!(this.spaceService.isMember(space, userName) || this.spaceService.isManager(space, userName) || this.spaceService.isSuperManager(userName))) {
            LOG.error("User is not allowed to get the team associated with the space {}", new Object[]{space.getDisplayName()});
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "User " + userName + " is not allowed to get information from space" + space.getPrettyName());
        }
        Room room = this.matrixService.getRoomBySpace(space);
        if (room == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "no Matrix room for space " + space.getDisplayName());
        }
        return this.buildRoomEntityFromRoom(room, userName);
    }

    @GetMapping(value={"dmRoom"})
    @Secured(value={"users"})
    @Operation(summary="Get the matrix room used for direct messaging between provided users", method="GET", description="Get the matrix room used for direct messaging between provided users")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="400", description="Invalid query input"), @ApiResponse(responseCode="500", description="Internal server error")})
    public RoomEntity getDirectMessagingRoom(HttpServletRequest request, @Parameter(description="The first participant") @RequestParam(name="firstParticipant") String firstParticipant, @Parameter(description="The second participant") @RequestParam(name="secondParticipant") String secondParticipant) {
        String currentUser = request.getRemoteUser();
        if (StringUtils.isBlank((CharSequence)firstParticipant) || StringUtils.isBlank((CharSequence)secondParticipant)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "the ids of the participants should not be null");
        }
        Room directMessagingRoom = this.matrixService.getDirectMessagingRoom(firstParticipant, secondParticipant);
        if (directMessagingRoom != null) {
            return this.buildRoomEntityFromRoom(directMessagingRoom, currentUser);
        }
        throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "Could not find a room for participants %s and %s".formatted(firstParticipant, secondParticipant));
    }

    @PostMapping
    @Secured(value={"users"})
    @Operation(summary="Gets or creates the Matrix room for the direct messaging", method="POST", description="Gets or creates the Matrix room for the direct messaging")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="400", description="Invalid query input"), @ApiResponse(responseCode="403", description="Private rooms are deactivated"), @ApiResponse(responseCode="500", description="Internal server error")})
    public RoomEntity createDirectMessagingRoom(HttpServletRequest request, @RequestBody(description="Matrix object to create", required=true) @org.springframework.web.bind.annotation.RequestBody Room room) {
        if (StringUtils.isBlank((CharSequence)room.getFirstParticipant()) || StringUtils.isBlank((CharSequence)room.getSecondParticipant())) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "the ids of the participants should not be null");
        }
        if (!this.matrixService.loadChatSettings().isPrivateRoomsEnabled()) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "Chat private rooms are deactivated in the system");
        }
        try {
            String currentUserName = request.getRemoteUser();
            return this.buildRoomEntityFromRoom(this.matrixService.createDirectMessagingRoom(room), currentUserName);
        }
        catch (ObjectAlreadyExistsException objectAlreadyExists) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR, objectAlreadyExists.getMessage());
        }
    }

    @PostMapping(value={"notify"})
    @Operation(summary="Receives push notification from Matrix", method="POST", description="Receives push notification from Matrix")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="400", description="Invalid query input"), @ApiResponse(responseCode="500", description="Internal server error")})
    public String notify(@RequestBody(description="Notification received from Matrix", required=true) @org.springframework.web.bind.annotation.RequestBody String notification) {
        JsonGeneratorImpl jsonGenerator = new JsonGeneratorImpl();
        try {
            JsonValue device;
            String pushKey;
            JsonValue jsonValue = jsonGenerator.createJsonObjectFromString(notification);
            JsonValue notifJsonValue = jsonValue.getElement("notification");
            if (notifJsonValue.getElement("devices").getElements().hasNext() && StringUtils.isNotBlank((CharSequence)(pushKey = (device = (JsonValue)notifJsonValue.getElement("devices").getElements().next()).getElement("pushkey").getStringValue()))) {
                String userName;
                try {
                    userName = this.checkAndParseUserFromToken(pushKey);
                }
                catch (ExpiredJwtException expiredException) {
                    return "{\n  \"rejected\": [\"%s\"]\n}\n".formatted(pushKey);
                }
                if (StringUtils.isNotBlank((CharSequence)userName)) {
                    int unreadCount = 0;
                    JsonValue element = notifJsonValue.getElement("counts");
                    if (element != null && element.getElement("unread") != null) {
                        unreadCount = element.getElement("unread").getIntValue();
                    }
                    String roomId = "";
                    if (notifJsonValue.getElement("room_id") != null) {
                        roomId = notifJsonValue.getElement("room_id").getStringValue();
                    }
                    String eventId = "";
                    if (notifJsonValue.getElement("event_id") != null) {
                        eventId = notifJsonValue.getElement("event_id").getStringValue();
                    }
                    this.chatNotificationService.createMentionNotification(eventId, roomId, userName, pushKey);
                    this.chatNotificationService.sendCreateNotificationAction(eventId, userName, roomId, unreadCount);
                }
            }
            return "{\n  \"rejected\": []\n}\n";
        }
        catch (Exception e) {
            LOG.error((Object)"Problem parsing notification received from Matrix", (Throwable)e);
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping(value={"linkRoom"})
    @Secured(value={"users"})
    @Operation(summary="Set the matrix room bound to the current space", method="POST", description="Set the id of the matrix room bound to the current space")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="400", description="Invalid query input"), @ApiResponse(responseCode="500", description="Internal server error")})
    public boolean linkSpaceToRoom(@RequestParam(value="spaceGroupId") String spaceGroupId, @RequestParam(name="roomId") String roomId, @RequestParam(name="create", required=false) Boolean create) {
        if (StringUtils.isBlank((CharSequence)spaceGroupId)) {
            LOG.error((Object)"Could not connect the space to a team, space name is missing");
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "space group Id is required");
        }
        Space space = this.spaceService.getSpaceByGroupId("/spaces/" + spaceGroupId);
        if (space == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "space with group Id " + spaceGroupId + " was not found");
        }
        Room room = this.matrixService.getRoomBySpace(space);
        if (room != null && StringUtils.isNotBlank((CharSequence)room.getRoomId())) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.CONFLICT, "space with group Id " + spaceGroupId + "has already a room with ID " + room.getRoomId());
        }
        if (StringUtils.isBlank((CharSequence)roomId) && create.booleanValue()) {
            try {
                roomId = this.matrixService.createRoomForSpaceOnMatrix(space);
            }
            catch (Exception e) {
                LOG.error("Could not link space {} to Matrix room {}", new Object[]{spaceGroupId, roomId, e});
                throw new ResponseStatusException((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR, "Could not link space " + spaceGroupId + " to Matrix room " + roomId + " : " + e.getMessage());
            }
        }
        return (room = this.matrixService.linkSpaceToMatrixRoom(space, roomId)) != null;
    }

    @GetMapping(value={"dmRooms"})
    @Secured(value={"users"})
    @Operation(summary="Get all the matrix rooms used for direct messaging of a defined user", method="GET", description="Get all the matrix rooms used for direct messaging of a defined user")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="400", description="Invalid query input"), @ApiResponse(responseCode="500", description="Internal server error")})
    public Map<String, String[]> getUserDirectMessagingRooms(@Parameter(description="The user") @RequestParam(name="user") String user) {
        HashMap<String, String[]> userDMRooms = new HashMap<String, String[]>();
        List<Room> rooms = this.matrixService.getMatrixDMRoomsOfUser(user);
        for (Room dmRoom : rooms) {
            Identity userIdentity = dmRoom.getFirstParticipant().equals(user) ? this.identityManager.getOrCreateUserIdentity(dmRoom.getSecondParticipant()) : this.identityManager.getOrCreateUserIdentity(dmRoom.getFirstParticipant());
            if (userIdentity == null) continue;
            String userMatrixId = "@" + String.valueOf(userIdentity.getProfile().getProperty("matrixId")) + ":" + PropertyManager.getProperty((String)"meeds.matrix.server.name");
            userDMRooms.put(userMatrixId, new String[]{dmRoom.getRoomId()});
        }
        return userDMRooms;
    }

    @GetMapping(value={"byRoom"})
    @Secured(value={"users"})
    @Operation(summary="Get the space linked to the specified matrix room", method="GET", description="Get the space linked to the specified matrix room")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="404", description="Space not found"), @ApiResponse(responseCode="500", description="Internal server error")})
    public String getByRoomId(HttpServletRequest request, @Parameter(description="The room Id") @RequestParam(name="roomId") String roomId) {
        Room room;
        if (StringUtils.isNotBlank((CharSequence)roomId) && roomId.contains(PropertyManager.getProperty((String)"meeds.matrix.server.name"))) {
            roomId = roomId.substring(0, roomId.indexOf(":"));
        }
        if ((room = this.matrixService.getById(roomId)) == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "There is no room with ID " + roomId);
        }
        if (room.getSpaceId() != null) {
            Space space = this.spaceService.getSpaceById(room.getSpaceId().longValue());
            if (space != null) {
                return this.identityManager.getOrCreateSpaceIdentity(space.getPrettyName()).getId();
            }
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "There is no space with ID " + room.getSpaceId());
        }
        String currentUserName = request.getRemoteUser();
        if (room.getFirstParticipant().equals(currentUserName)) {
            return this.identityManager.getOrCreateUserIdentity(room.getSecondParticipant()).getId();
        }
        return this.identityManager.getOrCreateUserIdentity(room.getFirstParticipant()).getId();
    }

    @GetMapping(value={"byRoomId"})
    @Secured(value={"users"})
    @Operation(summary="Get the room by Id", method="GET", description="Get the room by Id")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="404", description="User not found"), @ApiResponse(responseCode="500", description="Internal server error")})
    public ResponseEntity<RoomEntity> getRoomById(HttpServletRequest request, @Parameter(description="The room Id") @RequestParam(name="roomId") String roomId) {
        if (StringUtils.isBlank((CharSequence)roomId)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "roomId parameter is required");
        }
        String userName = request.getRemoteUser();
        Room room = this.matrixService.getById(roomId);
        if (room == null || !this.matrixService.canAccess(room, userName)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN);
        }
        RoomEntity roomEntity = this.buildRoomEntityFromRoom(room, userName);
        return ResponseEntity.ok().body(roomEntity);
    }

    @GetMapping(value={"spaceRoom"})
    @Secured(value={"users"})
    @Operation(summary="Get the room by Id", method="GET", description="Get the room by Id")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="404", description="User not found"), @ApiResponse(responseCode="500", description="Internal server error")})
    public ResponseEntity<RoomEntity> getRoomBySpaceId(HttpServletRequest request, @Parameter(description="The room Id") @RequestParam(name="spaceId") long spaceId) {
        String userName = request.getRemoteUser();
        Space space = this.spaceService.getSpaceById(spaceId);
        if (space == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND);
        }
        Room room = this.spaceService.canManageSpace(space, userName) ? this.matrixService.getRoomBySpace(space, true) : this.matrixService.getRoomBySpace(space);
        if (room == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND);
        }
        if (!this.matrixService.canAccess(room, userName)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN);
        }
        RoomEntity roomEntity = this.buildRoomEntityFromRoom(room, userName);
        return ResponseEntity.ok().body(roomEntity);
    }

    @PostMapping(value={"processRooms"})
    @Secured(value={"users"})
    @Operation(summary="Process the list of rooms and add needed information", method="POST", description="Process the list of rooms and add needed information")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="500", description="Internal server error")})
    public ResponseEntity<RoomList> processRooms(HttpServletRequest request, @RequestBody(description="Rooms received from Matrix", required=true) @org.springframework.web.bind.annotation.RequestBody RoomList rooms) {
        String userName = request.getRemoteUser();
        rooms = this.processRooms(rooms, userName);
        return ResponseEntity.ok().body(rooms);
    }

    @PutMapping(value={"setStatus"})
    @Secured(value={"users"})
    @Operation(summary="Set the presence status of the user", method="PUT", description="Set the presence status of the user")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="500", description="Internal server error")})
    public ResponseEntity<String> updatePresenceStatus(@RequestBody(description="Rooms received from Matrix", required=true) @org.springframework.web.bind.annotation.RequestBody Presence presence) {
        String presenceStatus = this.matrixService.updateUserPresence(presence.getUserIdOnMatrix(), presence.getPresence(), presence.getStatusMessage());
        return ResponseEntity.ok().body(presenceStatus);
    }

    @GetMapping(value={"sync"})
    @Secured(value={"administrators"})
    @Operation(summary="Get the user Identity for the provided matrix user Id", method="GET", description="Get the user Identity for the provided matrix user Id")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="404", description="User not found"), @ApiResponse(responseCode="500", description="Internal server error")})
    public ResponseEntity<String> syncUsersAndSpaces() {
        try {
            this.matrixSynchronizationService.synchronizeUsers();
            this.matrixSynchronizationService.synchronizeSpaces();
            return ResponseEntity.ok().body("Synchronization finished for users and spaces with Matrix server");
        }
        catch (Exception e) {
            LOG.error((Object)"Could not synchronise users and spaces", (Throwable)e);
            return ResponseEntity.internalServerError().body("An error occurred when synchronizing users and spaces with Matrix server");
        }
    }

    @GetMapping(value={"participant/{userId}"})
    @Secured(value={"users"})
    @Operation(summary="Get participant info including its created matrix id using its user Id", method="GET", description="Get participant info including its created matrix id using its user Id")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="404", description="User not found"), @ApiResponse(responseCode="500", description="Internal server error")})
    public ResponseEntity<?> getParticipantInfo(@PathVariable(value="userId") String userId) {
        try {
            if (userId == null) {
                return ResponseEntity.status((HttpStatusCode)HttpStatus.BAD_REQUEST).body("user id is mandatory");
            }
            return ResponseEntity.ok().body(this.buildRoomMember(userId));
        }
        catch (Exception e) {
            LOG.error("Could not get participant info of userId: {}", new Object[]{userId, e});
            return ResponseEntity.internalServerError().build();
        }
    }

    @PutMapping(value={"enable/{spaceId}"})
    @Secured(value={"users"})
    @Operation(summary="Enable the chat for a given space", method="GET", description="Enable the chat for a space ")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="404", description="User not found"), @ApiResponse(responseCode="500", description="Internal server error")})
    public ResponseEntity<String> enableChat(HttpServletRequest request, final @PathVariable(value="spaceId") String spaceId) {
        String currentUserName = request.getRemoteUser();
        if (StringUtils.isBlank((CharSequence)spaceId)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "Space id is mandatory");
        }
        final Space space = this.spaceService.getSpaceById(spaceId);
        if (!this.spaceService.canManageSpace(space, currentUserName)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "Current user does not have the needed privileges to enable the chat of the space");
        }
        Room roomToEnable = this.matrixService.getRoomBySpace(space, true);
        try {
            if (roomToEnable == null) {
                String roomId = this.matrixService.createRoom(space);
                roomToEnable = this.matrixService.getById(roomId);
            }
            class EnableChatRunnable
            implements Runnable {
                EnableChatRunnable() {
                }

                @Override
                public void run() {
                    ExoContainerContext.setCurrentContainer((ExoContainer)PortalContainer.getInstance());
                    RequestLifeCycle.begin((ExoContainer)PortalContainer.getInstance());
                    try {
                        MatrixRest.this.matrixService.enableSpaceChat(space, true);
                    }
                    catch (ObjectNotFoundException e) {
                        LOG.error("Could not enable the room of the space {}", new Object[]{space.getDisplayName(), e});
                        throw new ResponseStatusException((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR, "Could not enable the chat for the space with id " + spaceId);
                    }
                    finally {
                        RequestLifeCycle.end();
                    }
                }
            }
            AsyncTaskUtils.runAsync("Enable members in space Thread", new EnableChatRunnable());
            roomToEnable.setStatus(RoomStatus.ENABLE_IN_PROGRESS.name());
            return ResponseEntity.ok().build();
        }
        catch (Exception e) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR, "Could not enable the chat for the space with id " + spaceId);
        }
    }

    @PutMapping(value={"disable/{spaceId}"})
    @Secured(value={"users"})
    @Operation(summary="Enable the chat for a given space", method="GET", description="Enable the chat for a space ")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="404", description="User not found"), @ApiResponse(responseCode="500", description="Internal server error")})
    public ResponseEntity<String> disableChat(HttpServletRequest request, final @PathVariable(value="spaceId") String spaceId) {
        String currentUserName = request.getRemoteUser();
        if (StringUtils.isBlank((CharSequence)spaceId)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "Space id is mandatory");
        }
        final Space space = this.spaceService.getSpaceById(spaceId);
        if (!this.spaceService.canManageSpace(space, currentUserName)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "Current user does not have the needed privileges to enable the chat of the space");
        }
        Room roomToDisable = this.matrixService.getRoomBySpace(space, true);
        try {
            if (roomToDisable == null) {
                String roomId = this.matrixService.createRoom(space);
                roomToDisable = this.matrixService.getById(roomId, true);
            }
            class EnableChatRunnable
            implements Runnable {
                EnableChatRunnable() {
                }

                @Override
                public void run() {
                    ExoContainerContext.setCurrentContainer((ExoContainer)PortalContainer.getInstance());
                    RequestLifeCycle.begin((ExoContainer)PortalContainer.getInstance());
                    try {
                        MatrixRest.this.matrixService.enableSpaceChat(space, false);
                    }
                    catch (ObjectNotFoundException e) {
                        LOG.error("Could not disable the room of the space {}", new Object[]{space.getDisplayName(), e});
                        throw new ResponseStatusException((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR, "Could not disable the chat for the space with id " + spaceId);
                    }
                    finally {
                        RequestLifeCycle.end();
                    }
                }
            }
            AsyncTaskUtils.runAsync("Disable members in space Thread", new EnableChatRunnable());
            roomToDisable.setStatus(RoomStatus.DISABLED_IN_PROGRESS.name());
            return ResponseEntity.ok().build();
        }
        catch (Exception e) {
            LOG.error("Could not disable the room of the space {}", new Object[]{space.getDisplayName(), e});
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR, "Could not disable the chat for the space with id " + spaceId);
        }
    }

    @PutMapping(value={"notification/{roomId}/{eventId}/{ts}"})
    @Secured(value={"users"})
    @Operation(summary="Get the details of a notification based on the event details", method="GET", description="Get the details of a notification based on the event details")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="404", description="User not found"), @ApiResponse(responseCode="500", description="Internal server error")})
    public PwaNotificationMessage getNotification(HttpServletRequest request, @PathVariable(value="roomId") String roomId, @PathVariable(value="eventId") String eventId, @PathVariable(value="ts") String timeStamp, @RequestBody(description="Access token of the user", required=false) @org.springframework.web.bind.annotation.RequestBody(required=false) String accessToken) {
        String currentUserName = request.getRemoteUser();
        if (eventId == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "event id is mandatory");
        }
        long ts = 0L;
        try {
            ts = Long.parseLong(timeStamp);
        }
        catch (NumberFormatException numberFormatException) {
            // empty catch block
        }
        PwaNotificationMessage pwaMessage = this.chatNotificationService.createNotification(eventId, roomId, currentUserName, ts, accessToken);
        if (pwaMessage != null) {
            return pwaMessage;
        }
        throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "Event not found or it is not a chat message");
    }

    @PostMapping(value={"/muteRoom"})
    @Secured(value={"users"})
    @Operation(summary="Mute a private room for the current user", description="Adds a private room to the user's muted list")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Room muted successfully"), @ApiResponse(responseCode="400", description="Missing or invalid parameters"), @ApiResponse(responseCode="500", description="Internal server error")})
    public ResponseEntity<String> muteRoom(HttpServletRequest request, @Parameter(description="ID of the room to mute") @RequestParam(name="roomId") String roomId) {
        String userName = request.getRemoteUser();
        if (StringUtils.isBlank((CharSequence)roomId)) {
            return ResponseEntity.badRequest().body("roomId parameter is required");
        }
        try {
            this.chatNotificationService.toggleMutePrivateRoom(userName, roomId);
            return ResponseEntity.ok("Room muted successfully");
        }
        catch (Exception e) {
            LOG.error("Error muting room {} for user {}", new Object[]{roomId, userName, e});
            return ResponseEntity.status((HttpStatusCode)HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to mute room");
        }
    }

    @GetMapping(value={"/isPushNotificationsEnabled/{userName}"})
    @Secured(value={"users"})
    @Operation(summary="Get the status of push notifications enabled/disabled", method="GET", description="Get the status of push notifications enabled/disabled")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="400", description="username is not provided"), @ApiResponse(responseCode="403", description="Unauthorized to access information"), @ApiResponse(responseCode="500", description="Internal server error")})
    public ResponseEntity<String> isPushNotificationsEnabled(HttpServletRequest request, @PathVariable(value="userName") String userName) {
        if (StringUtils.isBlank((CharSequence)userName)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "The username parameter is required");
        }
        if (!request.getRemoteUser().equals(userName)) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.FORBIDDEN, "Can not check the settings of another user");
        }
        return ResponseEntity.ok().body(String.valueOf(this.chatNotificationService.isPushNotificationsEnabled(userName)));
    }

    @PostMapping(value={"/enablePushNotificationsSettings"})
    @Secured(value={"users"})
    @Operation(summary="Change the status of push notifications enabled/disabled", method="POST", description="Change the status of push notifications enabled/disabled")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="403", description="Unauthorized to access information"), @ApiResponse(responseCode="500", description="Internal server error")})
    public ResponseEntity<String> updatePushNotificationsSettings(HttpServletRequest request, @RequestBody(description="Notification received from Matrix", required=true) @org.springframework.web.bind.annotation.RequestBody String pushNotificationSetting) {
        try {
            JsonGeneratorImpl jsonGenerator = new JsonGeneratorImpl();
            JsonValue jsonValue = jsonGenerator.createJsonObjectFromString(pushNotificationSetting);
            JsonValue userNameJsonValue = jsonValue.getElement("userName");
            if (userNameJsonValue == null || userNameJsonValue.getStringValue() != null && !request.getRemoteUser().equals(userNameJsonValue.getStringValue())) {
                return ResponseEntity.status((HttpStatusCode)HttpStatus.FORBIDDEN).body("Check the status of Push notifications of another user is forbidden");
            }
            String userName = userNameJsonValue.getStringValue();
            boolean pushNotificationStatus = jsonValue.getElement("active") != null && jsonValue.getElement("active").getBooleanValue();
            this.chatNotificationService.updatePushNotificationSettings(userName, pushNotificationStatus);
            return ResponseEntity.ok().body("{}");
        }
        catch (Exception e) {
            LOG.error((Object)"Could not update the status of Push notifications of {}", (Throwable)e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping(value={"findId/{userId}"})
    @Secured(value={"users"})
    @Operation(summary="Get the matrix ID of a user", method="GET", description="Get the matrix ID of a user")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="404", description="User not found"), @ApiResponse(responseCode="500", description="Internal server error")})
    public ResponseEntity<String> getMatrixId(@PathVariable(value="userId") String userId) {
        if (userId == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "user id is mandatory");
        }
        String matrixId = this.matrixService.getMatrixIdForUser(userId);
        if (StringUtils.isNotBlank((CharSequence)matrixId)) {
            return ResponseEntity.ok().body(matrixId);
        }
        throw new ResponseStatusException((HttpStatusCode)HttpStatus.NOT_FOUND, "There is no Matrix account for the user " + userId);
    }

    @GetMapping(value={"spaceChatSetting/{spaceId}"}, produces={"application/json"})
    @Secured(value={"users"})
    @Operation(summary="Get the status of the Chat on this space", method="GET", description="Get the status of the Chat on this space")
    @ApiResponses(value={@ApiResponse(responseCode="200", description="Request fulfilled"), @ApiResponse(responseCode="400", description="No space found or the space Id is missing"), @ApiResponse(responseCode="500", description="Internal server error")})
    public String getChatAuthorizationStatus(HttpServletRequest request, @PathVariable(value="spaceId") long spaceId) {
        if (spaceId == 0L) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "space id is mandatory");
        }
        Space space = this.spaceService.getSpaceById(spaceId);
        if (space == null) {
            throw new ResponseStatusException((HttpStatusCode)HttpStatus.BAD_REQUEST, "space was not found");
        }
        Room spaceRoom = this.matrixService.getRoomBySpace(space);
        boolean chatAuthorizedForSpace = this.matrixService.isChatAuthorizedByAdministration(space) && (spaceRoom != null || this.matrixService.isChatAuthorizedForSpaceTemplate(space));
        return "{\n  \"chatAuthorizedForSpace\": %s\n}\n".formatted(chatAuthorizedForSpace);
    }

    private String checkAndParseUserFromToken(String token) {
        String fullUserName;
        String userName;
        byte[] secret = PropertyManager.getProperty((String)"meeds.matrix.jwt.secret").getBytes();
        Jws jws = Jwts.parserBuilder().setSigningKey((Key)Keys.hmacShaKeyFor((byte[])secret)).build().parseClaimsJws(token);
        String usernameOnMatrix = String.valueOf(((Claims)jws.getBody()).getSubject());
        if (StringUtils.isNotBlank((CharSequence)usernameOnMatrix) && this.identityManager.identityExisted("organization", userName = this.matrixService.findUserByMatrixId(fullUserName = this.matrixService.getUserFullMatrixID(usernameOnMatrix)))) {
            return userName;
        }
        return null;
    }

    private RoomEntity buildRoomEntityFromRoom(Room room, String currentUserName) {
        String serverName;
        RoomEntity roomEntity = new RoomEntity();
        Object roomId = room.getRoomId();
        if (!((String)roomId).contains(serverName = PropertyManager.getProperty((String)"meeds.matrix.server.name"))) {
            roomId = (String)roomId + ":" + serverName;
        }
        roomEntity.setId((String)roomId);
        roomEntity.setStatus(room.getStatus());
        if (room.getSpaceId() != null) {
            roomEntity.setSpaceId(room.getSpaceId());
            roomEntity.setDirectChat(false);
            roomEntity.setSpaceId(room.getSpaceId());
        } else if (StringUtils.isNotBlank((CharSequence)room.getFirstParticipant()) && StringUtils.isNotBlank((CharSequence)room.getSecondParticipant())) {
            roomEntity.setDirectChat(true);
            if (room.getFirstParticipant().equals(currentUserName)) {
                roomEntity.setDmMemberId(room.getSecondParticipant());
            } else {
                roomEntity.setDmMemberId(room.getFirstParticipant());
            }
        }
        return this.updateRoomEntity(roomEntity, currentUserName);
    }

    public RoomList processRooms(RoomList roomList, String currentUserName) {
        if (roomList == null || roomList.getRooms() == null) {
            throw new IllegalArgumentException("The room list Object is empty");
        }
        if (StringUtils.isBlank((CharSequence)currentUserName)) {
            throw new IllegalArgumentException("The username of the current user is mandatory");
        }
        List<String> spaceIds = this.spaceService.getMemberSpacesIds(currentUserName, 0, -1);
        ArrayList<RoomEntity> processedRooms = new ArrayList<RoomEntity>();
        for (int index = 0; index < roomList.getRooms().size(); ++index) {
            RoomEntity room = roomList.getRooms().get(index);
            if ((room = this.updateRoomEntity(room, currentUserName)) == null) continue;
            if (room.isMuted()) {
                roomList.setTotalUnreadMessages(Math.max(0L, roomList.getTotalUnreadMessages() - room.getUnreadMessages()));
            }
            if (!room.isDirectChat()) {
                spaceIds.remove(String.valueOf(room.getSpaceId()));
            }
            ChatSettingsEntity chatSettings = this.matrixService.loadChatSettings();
            if (RoomStatus.ENABLED.name().equals(room.getStatus()) && (chatSettings == null || room.isDirectChat() && chatSettings.isPrivateRoomsEnabled() || !room.isDirectChat() && chatSettings.isSpaceRoomsEnabled() && this.matrixService.isChatAuthorizedForSpace(room.getSpaceId()))) {
                processedRooms.add(room);
                continue;
            }
            roomList.setTotalUnreadMessages(Math.max(0L, roomList.getTotalUnreadMessages() - room.getUnreadMessages()));
        }
        if (!spaceIds.isEmpty()) {
            for (String spaceId : spaceIds) {
                RoomEntity missingRoomEntity;
                Room missingRoom = this.matrixService.getRoomBySpaceId(Long.valueOf(spaceId));
                if (missingRoom == null || (missingRoomEntity = this.buildRoomEntityFromRoom(missingRoom, currentUserName)) == null) continue;
                processedRooms.addFirst(missingRoomEntity);
            }
        }
        roomList.setRooms(processedRooms);
        return roomList;
    }

    private RoomEntity updateRoomEntity(RoomEntity room, String currentUserName) {
        Room matrixRoom;
        String roomId = room.getId();
        if (room.getId().contains(":")) {
            roomId = room.getId().substring(0, room.getId().indexOf(":"));
        }
        if ((matrixRoom = this.matrixService.getById(roomId, true)) != null) {
            if (matrixRoom.getSpaceId() != null) {
                Space space = this.spaceService.getSpaceById(matrixRoom.getSpaceId().longValue());
                UserSettingService userSettingService = (UserSettingService)CommonsUtils.getService(UserSettingService.class);
                UserSetting userSetting = userSettingService.get(currentUserName);
                if (space != null) {
                    room.setName(space.getDisplayName());
                    room.setAvatarUrl(space.getAvatarUrl());
                    room.setSpaceId(matrixRoom.getSpaceId());
                    room.setPrettyName(space.getPrettyName());
                    room.setMuted(userSetting != null && userSetting.isSpaceMuted(Long.parseLong(space.getId())));
                    room.setDirectChat(false);
                    ArrayList<Member> members = new ArrayList<Member>();
                    Arrays.stream(space.getMembers()).forEach(member -> members.add(this.buildRoomMember((String)member)));
                    room.setMembers(members);
                }
            } else if (StringUtils.isNotBlank((CharSequence)matrixRoom.getFirstParticipant()) && StringUtils.isNotBlank((CharSequence)matrixRoom.getSecondParticipant())) {
                Identity identity = null;
                if (matrixRoom.getFirstParticipant().equals(currentUserName)) {
                    identity = this.identityManager.getOrCreateUserIdentity(matrixRoom.getSecondParticipant());
                } else if (matrixRoom.getSecondParticipant().equals(currentUserName)) {
                    identity = this.identityManager.getOrCreateUserIdentity(matrixRoom.getFirstParticipant());
                }
                if (identity != null) {
                    room.setName(identity.getProfile().getFullName());
                    room.setAvatarUrl(identity.getProfile().getAvatarUrl());
                    room.setUserId(identity.getRemoteId());
                    room.setIdentityId(identity.getId());
                    room.setDmMemberId(identity.getRemoteId());
                    room.setMuted(this.chatNotificationService.isPrivateRoomMutedForUser(currentUserName, room.getId()));
                    room.setExternal(identity.isExternal());
                    room.setEnabledUser(identity.isEnable());
                    room.setDeletedUser(identity.isDeleted());
                    room.setMatrixId((String)identity.getProfile().getProperty("matrixId"));
                    room.setDirectChat(true);
                }
            }
            room.setStatus(matrixRoom.getStatus());
            if (room.getUpdated() == 0L) {
                room.setUpdated(System.currentTimeMillis());
            }
            return room;
        }
        return null;
    }

    private Member buildRoomMember(String userName) {
        Identity identity = this.identityManager.getOrCreateUserIdentity(userName);
        if (identity == null || identity.getProfile() == null) {
            return null;
        }
        Member member = new Member();
        member.setId(identity.getId());
        member.setUserId(userName);
        member.setMatrixId((String)identity.getProfile().getProperty("matrixId"));
        return member;
    }
}
