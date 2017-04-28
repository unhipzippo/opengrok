/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

 /*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 */
package org.opensolaris.opengrok.authorization;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import org.opensolaris.opengrok.configuration.Nameable;
import org.opensolaris.opengrok.configuration.Project;
import org.opensolaris.opengrok.logger.LoggerFactory;

/**
 * This is a subclass of {@link AuthorizationEntity} and is a wrapper to a
 * {@link IAuthorizationPlugin} delegating the decision methods to the contained
 * plugin.
 *
 * @author Krystof Tulinger
 */
public class AuthorizationPlugin extends AuthorizationStack {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthorizationPlugin.class);
    private static final long serialVersionUID = 2L;

    private transient IAuthorizationPlugin plugin;

    public AuthorizationPlugin() {
    }

    /**
     * Clone the plugin:
     * <ul>
     * <li>copy the superclass {@link AuthorizationStack}</li>
     * <li>sets the plugin to {@code null}</li>
     * </ul>
     *
     * @param x the plugin to be copied
     */
    public AuthorizationPlugin(AuthorizationPlugin x) {
        super(x);
        plugin = null;
    }

    public AuthorizationPlugin(AuthControlFlag flag, IAuthorizationPlugin plugin) {
        this(flag, plugin.getClass().getCanonicalName() == null ? plugin.getClass().getName() : plugin.getClass().getCanonicalName(), plugin);
    }

    public AuthorizationPlugin(AuthControlFlag flag, String name) {
        this(flag, name, null);
    }

    public AuthorizationPlugin(AuthControlFlag flag, String name, IAuthorizationPlugin plugin) {
        super(flag, name);
        this.plugin = plugin;
    }

    /**
     * Call the load method on the underlying plugin if the plugin exists. Note
     * that the load method can throw any throwable from its body and it should
     * not stop the application.
     *
     * <p>
     * If the method is unable to load the plugin because of any reason (mostly
     * the class is not found, not instantiable or the load method throws an
     * exception) then any authorization check should fail for this plugin in
     * the future.
     * </p>
     *
     * @param parameters parameters given in the configuration
     *
     * @see IAuthorizationPlugin#load(java.util.Map)
     */
    @Override
    public synchronized void load(Map<String, Object> parameters) {
        if (!hasPlugin()) {
            LOGGER.log(Level.SEVERE, "Configured plugin \"{0}\" has not been loaded into JVM (missing file?). "
                    + "This can cause the authorization to fail always.",
                    getName());
            setFailed();
            return;
        }

        Map<String, Object> s = new TreeMap<>();
        s.putAll(parameters);
        s.putAll(getSetup());

        try {
            plugin.load(s);
            setWorking();
        } catch (Throwable ex) {
            LOGGER.log(Level.SEVERE, "Plugin \"" + getName() + "\" has failed while loading with exception:", ex);
            setFailed();
        }

        LOGGER.log(Level.INFO, "[{0}] Plugin \"{1}\" {2} and is {3}.",
                new Object[]{
                    getFlag().toString().toUpperCase(),
                    getName(),
                    hasPlugin() ? "found" : "not found",
                    isWorking() ? "working" : "failed"});
    }

    /**
     * Call the unload method on the underlying plugin if the plugin exists.
     * Note that the unload method can throw any throwable from its body and it
     * should not stop the application.
     *
     * @see IAuthorizationPlugin#unload()
     */
    @Override
    public synchronized void unload() {
        if (hasPlugin()) {
            try {
                plugin.unload();
                plugin = null;
            } catch (Throwable ex) {
                LOGGER.log(Level.SEVERE, "Plugin \"" + getName() + "\" has failed while unloading with exception:", ex);
            }
        }
    }

    /**
     * Test the underlying plugin with the predicate if and only if the plugin
     * is not marked as failed.
     *
     * @param entity the given entity - this is either group or project and is
     * passed just for the logging purposes.
     * @param predicate predicate returning true or false for the given entity
     * which determines if the authorization for such entity is successful or
     * failed for particular request and plugin
     * @return true if the plugin is not failed and the project is allowed;
     * false otherwise
     *
     * @see #isFailed()
     * @see IAuthorizationPlugin#isAllowed(HttpServletRequest, Project)
     * @see IAuthorizationPlugin#isAllowed(HttpServletRequest, Group)
     */
    @Override
    public boolean isAllowed(Nameable entity, Predicate<IAuthorizationPlugin> predicate) {
        if (isFailed()) {
            return false;
        }
        return predicate.test(plugin);
    }

    /**
     * Set the plugin to this entity if this entity requires this plugin class
     * in the configuration. This creates a new instance of the plugin for each
     * class which needs it.
     *
     * @param plugin the new instance of a plugin
     * @return true if there is the class names are equal and the plugin is not
     * null; false otherwise
     */
    @Override
    public boolean setPlugin(IAuthorizationPlugin plugin) {
        if (!getName().equals(plugin.getClass().getCanonicalName())
                || !getName().equals(plugin.getClass().getName())) {
            return false;
        }
        if (hasPlugin()) {
            unload();
        }
        try {
            /**
             * The exception should not happen here as we already have an
             * instance of IAuthoriazationPlugin. But it is required by the
             * compiler.
             *
             * NOTE: If we were to add a throws clause here we would interrupt
             * the whole stack walk through and prevent the other authorization
             * entities to work properly.
             */
            return (this.plugin = plugin.getClass().newInstance()) != null;
        } catch (InstantiationException ex) {
            LOGGER.log(Level.INFO, "Class could not be instantiated: ", ex);
        } catch (IllegalAccessException ex) {
            LOGGER.log(Level.INFO, "Class loader threw an exception: ", ex);
        } catch (Throwable ex) {
            LOGGER.log(Level.INFO, "Class loader threw an uknown error: ", ex);
        }
        return false;
    }

    /**
     * Get the authorization plugin
     *
     * @return the underlying plugin
     */
    protected IAuthorizationPlugin getPlugin() {
        return plugin;
    }

    /**
     * Check if the plugin exists and has not failed while loading.
     *
     * @return true if working, false otherwise
     */
    @Override
    public boolean isWorking() {
        return working && hasPlugin();
    }

    /**
     * Check if the plugin class was found for this plugin.
     *
     * @return true if was; false otherwise
     */
    public boolean hasPlugin() {
        return plugin != null;
    }

    /**
     * Clone the plugin:
     * <ul>
     * <li>copy the superclass {@link AuthorizationStack}</li>
     * <li>sets the plugin to {@code null}</li>
     * </ul>
     *
     * @return new instance of {@link AuthorizationPlugin}
     */
    @Override
    public AuthorizationPlugin clone() {
        return new AuthorizationPlugin(this);
    }
}
