package com.mitpass.portal.web;

import com.mitpass.portal.auth.PortalUser;
import com.mitpass.portal.auth.SessionExpiryFilter;
import com.mitpass.portal.client.SessionExpiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Shared by every page: the current user for the header, and "log in again" when the token expired mid-page. */
@ControllerAdvice
public class PortalAdvice {

    /** Display fields only; templates never touch the tokens. */
    @ModelAttribute("currentUser")
    public PortalUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof PortalUser user ? user : null;
    }

    @ExceptionHandler(SessionExpiredException.class)
    public void sessionExpired(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SessionExpiryFilter.expire(request, response);
    }
}
