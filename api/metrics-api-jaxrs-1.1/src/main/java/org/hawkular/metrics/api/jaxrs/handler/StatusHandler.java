/*
 * Copyright 2014-2015 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.hawkular.metrics.api.jaxrs.handler;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.inject.Inject;
import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.hawkular.metrics.api.jaxrs.MetricsServiceLifecycle;
import org.hawkular.metrics.api.jaxrs.MetricsServiceLifecycle.State;

import com.wordnik.swagger.annotations.ApiOperation;

/**
 * @author mwringe
 */
@Path("/status")
@Produces(APPLICATION_JSON)
public class StatusHandler {

    public static final String PATH = "/status";

    @Context
    ServletContext servletContext;

    @Inject
    private MetricsServiceLifecycle metricsServiceLifecycle;

    private static final String METRICSSERVICE_NAME = "MetricsService";

    @GET
    @ApiOperation(value = "Returns the current status for various components.",
                  response = String.class, responseContainer = "Map")
    public Response status() {
        Map<String, Object> status = new HashMap<>();

        State metricState = metricsServiceLifecycle.getState();
        status.put(METRICSSERVICE_NAME, metricState.toString());

        this.getVersionInformation(status);

        return Response.ok(status).build();
    }

    private void getVersionInformation(Map<String, Object> status) {
        try (InputStream inputStream = servletContext.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            Manifest manifest = new Manifest(inputStream);
            Attributes attr = manifest.getMainAttributes();
            status.put("Implementation-Version", attr.getValue("Implementation-Version"));
            status.put("Built-From-Git-SHA1", attr.getValue("Built-From-Git-SHA1"));
        } catch (Exception e) {
            status.put("Implementation-Version", "Unknown");
            status.put("Built-From-Git-SHA1", "Unknown");
        }
    }
}
