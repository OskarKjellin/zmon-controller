package org.zalando.zmon.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.zalando.zmon.config.ControllerProperties;
import org.zalando.zmon.config.KairosDBProperties;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

@Controller
public class GrafanaUIController {

    private ControllerProperties controllerProperties;

    public static class KairosDBEntry {
        public String name;
        public String url;

        public KairosDBEntry(String n, String u) {
            name = n;
            url = u;
        }

        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }
    }

    private final List<KairosDBEntry> kairosdbServices = new ArrayList<>();

    @Autowired
    public GrafanaUIController(KairosDBProperties kairosdbProperties, ControllerProperties controllerProperties) {
        this.controllerProperties = controllerProperties;

        for (KairosDBProperties.KairosDBServiceConfig c : kairosdbProperties.getKairosdbs()) {
            kairosdbServices.add(new KairosDBEntry(c.getName(), "/rest/kairosdbs/" + c.getName()));
        }
    }

    @RequestMapping(value = "/grafana")
    public String grafana(Model model) {
        model.addAttribute(IndexController.STATIC_URL, controllerProperties.getStaticUrl());
        model.addAttribute(IndexController.KAIROSDB_SERVICES, kairosdbServices);
        return "grafana";
    }

    @RequestMapping(value = {"/grafana/dashboard/db/**", "/grafana/dashboard-solo/db/**"})
    public String grafanaDeepLinks(Model model) {
        model.addAttribute(IndexController.STATIC_URL, controllerProperties.getStaticUrl());
        model.addAttribute(IndexController.KAIROSDB_SERVICES, kairosdbServices);
        return "grafana";
    }

    @RequestMapping(value = "/grafana2/**")
    public String grafana2Redirect(HttpServletRequest request) {
        return "redirect:" + request.getRequestURI().replace("/grafana2/", "/grafana/");
    }
}
