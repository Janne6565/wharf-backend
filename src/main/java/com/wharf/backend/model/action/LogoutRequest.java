package com.wharf.backend.model.action;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "LogoutRequest",
        description = "Optional body for logout. Omitted (or false) means a local sign-out of this browser; "
                + "true revokes every session of the account on every device.")
public record LogoutRequest(

        @Schema(nullable = true,
                description = "Revoke every issued token for the account — other browsers, the paired TUI and "
                        + "mobile included. Requires authentication. Defaults to false")
        Boolean allDevices
) {
    public boolean allDevicesOrDefault() {
        return Boolean.TRUE.equals(allDevices);
    }
}
