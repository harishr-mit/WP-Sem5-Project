package com.mitpass.portal.web;

import com.mitpass.portal.client.ApiModels.Binary;
import com.mitpass.portal.client.ApiModels.Pass;
import com.mitpass.portal.client.ApiProblemException;
import com.mitpass.portal.client.GatewayClient;
import com.mitpass.portal.config.PortalProperties;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * "My passes" (UI reference screens 3–4, thin slice, docs/plans/phase-4.md §10). The QR image and the PDF are fetched
 * from pass-service server side and passed through as bytes, so the token stays inside the image and the browser never
 * holds an API token (D9 BFF rule). The pass page swaps the image every 30 s with a plain timer (D9 shortcut).
 */
@Controller
public class MyPassesController {

    private final GatewayClient gateway;
    private final PortalProperties properties;

    public MyPassesController(GatewayClient gateway, PortalProperties properties) {
        this.gateway = gateway;
        this.properties = properties;
    }

    @GetMapping("/my/passes")
    public String list(Model model) {
        model.addAttribute("passes", gateway.myPasses().content());
        return "my/passes";
    }

    @GetMapping("/my/passes/{id}")
    public String pass(@PathVariable UUID id, Model model) {
        Pass pass = gateway.pass(id);
        model.addAttribute("pass", pass);
        model.addAttribute("refreshSeconds", properties.qrRefresh().toSeconds());
        return "my/pass";
    }

    /** The live QR (a fresh 45 s token each time). A 409 (locked, revoked) is passed on without a body. */
    @GetMapping("/my/passes/{id}/qr.png")
    public ResponseEntity<byte[]> liveQr(@PathVariable UUID id) {
        try {
            Binary png = gateway.liveQrPng(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.noStore())
                    .body(png.body());
        } catch (ApiProblemException ex) {
            return ResponseEntity.status(ex.getStatus() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
    }

    @GetMapping("/my/passes/{id}/ticket.pdf")
    public ResponseEntity<byte[]> ticket(@PathVariable UUID id) {
        Binary pdf = gateway.ticketPdf(id);
        ResponseEntity.BodyBuilder ok =
                ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore());
        if (pdf.contentDisposition() != null) {
            ok.header(HttpHeaders.CONTENT_DISPOSITION, pdf.contentDisposition());
        }
        return ok.body(pdf.body());
    }
}
