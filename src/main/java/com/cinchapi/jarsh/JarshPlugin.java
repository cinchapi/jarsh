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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import groovy.lang.Closure;
import net.nisgits.gradle.executablejar.ExecutableJarPlugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.BasePlugin;

import com.cinchapi.util.TFiles;

/**
 * The {@link JarshPlugin} builds on the {@link ExecutableJarPlugin} to build an
 * executable shell (.sh) script that embeds the runnable jar with all of its
 * dependencies and correctly finds the JAVA executable with which to launch the
 * application. This plugin makes applications more portable since they can be
 * package in a single shell script that has simpler launch syntax.
 * 
 * @author jnelson
 */
public class JarshPlugin implements Plugin<Project> {

    public static final String JARSH_TASK_NAME = "jarsh";

    @Override
    public void apply(Project project) {
        project.getPlugins().apply(ExecutableJarPlugin.class);

        Task task = project.task(JARSH_TASK_NAME);

        // Define the task metadata
        task.setDescription("Generates an executable shell script "
                + "with all the runtime dependencies embedded.");
        task.setGroup(BasePlugin.BUILD_GROUP);
        final Task executableJarTask = project
                .getTasksByName(ExecutableJarPlugin.EXECUTABLE_JAR_TASK_NAME,
                        false).iterator().next();
        task.dependsOn(executableJarTask);

        final File outputDir = new File(project.getBuildDir() + File.separator
                + "libs");
        final File jarshFile = new File(outputDir + File.separator
                + project.getName() + ".sh");
        task.getOutputs().file(jarshFile);

        // Define the task logic
        task.doLast(new Closure<Void>(null) {

            private static final long serialVersionUID = 1L;

            @Override
            public Void call() {
                File runnableJarFile = executableJarTask.getOutputs()
                        .getFiles().iterator().next();

                Path stubPath = Paths.get(outputDir.getAbsolutePath()
                        + File.separator + "stub.sh");
                try {
                    Files.copy(this.getClass().getResourceAsStream("/stub.sh"),
                            stubPath);
                    TFiles.cat(jarshFile.getAbsolutePath(), stubPath.toFile()
                            .getAbsolutePath(), runnableJarFile
                            .getAbsolutePath());
                    Files.delete(stubPath);
                    jarshFile.setExecutable(true);
                }
                catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            }

        });

    }
}
