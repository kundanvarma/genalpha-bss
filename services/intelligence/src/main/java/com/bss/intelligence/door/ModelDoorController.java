package com.bss.intelligence.door;

import com.bss.intelligence.api.ApiConstants;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The model door's two faces (#283).
 *
 *   /workforce/doorKeys        staff (ai:admin): issue a worker's key at hire, revoke it at fire
 *   /modelDoor/anthropic/...   the worker: its model calls, authenticated by that key
 *
 * The door's path ENDS IN /anthropic on purpose: Hermes accepts a custom base
 * URL for its Anthropic provider only when the path ends that way, and
 * otherwise falls back to api.anthropic.com in silence — which would route the
 * worker straight past the door.
 */
@RestController
public class ModelDoorController {

    private final ModelDoorService door;

    public ModelDoorController(ModelDoorService door) {
        this.door = door;
    }

    @PostMapping(ApiConstants.BASE_PATH + "/workforce/doorKeys")
    @ResponseStatus(HttpStatus.CREATED)
    public DoorRequests.IssuedKey issue(@RequestBody DoorRequests.IssueKey request) {
        return door.issue(request);
    }

    @DeleteMapping(ApiConstants.BASE_PATH + "/workforce/doorKeys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id) {
        door.revoke(id);
    }

    /** The SDK may probe for its model's context window; the door lists nothing, and says so. */
    @GetMapping(value = {ApiConstants.BASE_PATH + "/modelDoor/anthropic/v1/models",
            ApiConstants.BASE_PATH + "/modelDoor/anthropic/v1/models/**"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> models() {
        DoorRequests.DoorReply reply = door.modelsNotServed();
        return ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }

    /** Anthropic Messages, as the worker's SDK sends it: key in x-api-key, body passed through. */
    @PostMapping(value = ApiConstants.BASE_PATH + "/modelDoor/anthropic/v1/messages",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> anthropicMessages(
            @RequestHeader(value = "x-api-key", required = false) String key,
            @RequestHeader(value = "anthropic-version", required = false) String version,
            @RequestHeader(value = "anthropic-beta", required = false) String beta,
            @RequestBody(required = false) String body) {
        DoorRequests.DoorReply reply = door.anthropicMessages(key, body, version, beta);
        return ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }
}
