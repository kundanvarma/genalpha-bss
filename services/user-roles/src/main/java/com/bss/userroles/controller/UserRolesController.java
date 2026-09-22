package com.bss.userroles.controller;

import com.bss.userroles.api.ApiConstants;
import com.bss.userroles.dto.CreateUserRequest;
import com.bss.userroles.dto.GrantRequest;
import com.bss.userroles.dto.PermissionView;
import com.bss.userroles.dto.RoleView;
import com.bss.userroles.dto.UserView;
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

@RestController
@RequestMapping(ApiConstants.BASE_PATH)
public class UserRolesController {

    private final UserRolesService service;

    public UserRolesController(UserRolesService service) {
        this.service = service;
    }

    @GetMapping("/userRole")
    public ResponseEntity<List<RoleView>> roles() {
        return ResponseEntity.ok(service.roles());
    }

    @GetMapping("/user")
    public ResponseEntity<List<UserView>> users(
            @RequestParam(required = false) String username) {
        return ResponseEntity.ok(service.users(username));
    }

    @PostMapping("/user")
    public ResponseEntity<UserView> createUser(@RequestBody CreateUserRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createUser(dto));
    }

    @GetMapping("/permission")
    public ResponseEntity<List<PermissionView>> permissions(@RequestParam String userId) {
        return ResponseEntity.ok(service.permissionsOf(userId));
    }

    @PostMapping("/permission")
    public ResponseEntity<PermissionView> grant(@RequestBody GrantRequest dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.grant(dto));
    }

    @DeleteMapping("/permission/{id}")
    public ResponseEntity<Void> revoke(@PathVariable String id) {
        service.revoke(id);
        return ResponseEntity.noContent().build();
    }
}
