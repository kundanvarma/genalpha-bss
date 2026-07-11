package com.bss.userroles.controller;

import com.bss.userroles.api.ApiConstants;
import com.bss.userroles.service.UserRolesService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class UserRolesController {

    private final UserRolesService service;

    public UserRolesController(UserRolesService service) {
        this.service = service;
    }

    @GetMapping("/userRole")
    public ResponseEntity<List<Map<String, Object>>> roles() {
        return ResponseEntity.ok(service.roles());
    }

    @GetMapping("/user")
    public ResponseEntity<List<Map<String, Object>>> users(
            @RequestParam(required = false) String username) {
        return ResponseEntity.ok(service.users(username));
    }

    @GetMapping("/permission")
    public ResponseEntity<List<Map<String, Object>>> permissions(@RequestParam String userId) {
        return ResponseEntity.ok(service.permissionsOf(userId));
    }

    @PostMapping("/permission")
    public ResponseEntity<Map<String, Object>> grant(@RequestBody Map<String, Object> dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.grant(dto));
    }

    @DeleteMapping("/permission/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        service.revoke(id);
        return ResponseEntity.noContent().build();
    }
}
