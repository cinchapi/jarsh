/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Cinchapi Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cinchapi.jarsh;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;

/**
 * The {@link JarshPlugin} turns a Java application into a binary: one
 * executable shell script that carries the application and all of its
 * dependencies and runs on any machine with a suitable Java.
 * <p>
 * The plugin applies the {@code java} plugin, adds the {@code jarsh} extension
 * that {@link JarshExtension} describes, and adds the {@code jarsh} task, a
 * {@link JarshTask} that builds the binary from the project's jar and runtime
 * classpath.
 *
 * @author jnelson
 */
public class JarshPlugin implements Plugin<Project> {

    /**
     * The name of the task that builds the binary.
     */
    public static final String JARSH_TASK_NAME = "jarsh";

    /**
     * The name of the extension that holds the binary's settings.
     */
    private static final String EXTENSION_NAME = "jarsh";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        JarshExtension extension = project.getExtensions()
                .create(EXTENSION_NAME, JarshExtension.class);
        extension.getName().convention(project.getName() + ".sh");
        project.getPluginManager().withPlugin("application",
                plugin -> extension.getMainClass()
                        .convention(project.getExtensions()
                                .getByType(JavaApplication.class)
                                .getMainClass()));
        TaskProvider<Jar> jar = project.getTasks()
                .named(JavaPlugin.JAR_TASK_NAME, Jar.class);
        Configuration runtimeClasspath = project.getConfigurations()
                .getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);
        BasePluginExtension base = project.getExtensions()
                .getByType(BasePluginExtension.class);
        project.getTasks().register(JARSH_TASK_NAME, JarshTask.class, task -> {
            task.setGroup(BasePlugin.BUILD_GROUP);
            task.setDescription("Builds the application into one executable "
                    + "shell script that carries all of its dependencies.");
            task.getClasspath().from(jar.flatMap(Jar::getArchiveFile),
                    runtimeClasspath);
            task.getMainClass().set(extension.getMainClass());
            task.getJavaVersion().set(extension.getJavaVersion());
            task.getLauncher().set(extension.getLauncher());
            task.getBinary().set(base.getLibsDirectory()
                    .flatMap(libs -> libs.file(extension.getName())));
        });
    }

}
