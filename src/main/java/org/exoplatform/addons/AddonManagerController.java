package org.exoplatform.addons;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/portal/rest/v1/addons")
public class AddonManagerController {

  @Autowired
  private AddonManagerService addonService;

  @GetMapping
  public ResponseEntity<?> listAddons() {
    List<AddonManagerService.Addon> addons = addonService.listAddons();
    return ResponseEntity.ok(new AddonsListResponse(addons, addons.size()));
  }

  @PostMapping("/{addonId}/enable")
  public ResponseEntity<?> enableAddon(@PathVariable String addonId) {
    addonService.enableAddon(addonId);
    return ResponseEntity.ok(new ActionResponse("SUCCESS", "Add-on ativado: " + addonId));
  }

  @PostMapping("/{addonId}/disable")
  public ResponseEntity<?> disableAddon(@PathVariable String addonId) {
    addonService.disableAddon(addonId);
    return ResponseEntity.ok(new ActionResponse("SUCCESS", "Add-on desativado: " + addonId));
  }

  @PostMapping("/upload")
  public ResponseEntity<?> uploadAddon(@RequestParam("file") MultipartFile file) throws IOException {
    String warPath = file.getOriginalFilename();
    addonService.uploadAddon(warPath);
    return ResponseEntity.ok(new ActionResponse("SUCCESS", "Add-on enviado: " + warPath));
  }

  public static class AddonsListResponse {
    public List<AddonManagerService.Addon> addons;
    public int count;

    public AddonsListResponse(List<AddonManagerService.Addon> addons, int count) {
      this.addons = addons;
      this.count = count;
    }
  }

  public static class ActionResponse {
    public String status;
    public String message;

    public ActionResponse(String status, String message) {
      this.status = status;
      this.message = message;
    }
  }
}
