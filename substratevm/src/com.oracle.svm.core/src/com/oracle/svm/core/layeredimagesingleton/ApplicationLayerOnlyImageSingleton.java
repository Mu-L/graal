/*
 * Copyright (c) 2024, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.layeredimagesingleton;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.graal.nodes.LoadImageSingletonNode;
import com.oracle.svm.core.option.HostedOptionValues;

/**
 * Identifies a singleton for which all lookups refer to a single singleton which will be created in
 * the application layer. See {@link LayeredImageSingleton} for full explanation.
 * <p>
 * Referring to fields of an {@link ApplicationLayerOnlyImageSingleton} object from a code compiled
 * in a shared layer, i.e., even before the value of the field can be known, is safe because an
 * {@link ApplicationLayerOnlyImageSingleton} will never be constant-folded in a shared layer. It is
 * instead implemented via {@link LoadImageSingletonNode} which is lowered to a singleton table
 * read.
 */
public interface ApplicationLayerOnlyImageSingleton extends LayeredImageSingleton {

    static boolean isSingletonInstanceOf(Object singleton) {
        if (singleton instanceof ApplicationLayerOnlyImageSingleton) {
            return true;
        }
        if (ImageSingletons.contains(HostedOptionValues.class)) {
            return SubstrateOptions.ApplicationLayerOnlySingletons.getValue().contains(singleton.getClass().getName());
        }

        return false;
    }

    static boolean isAssignableFrom(Class<?> klass) {
        if (ApplicationLayerOnlyImageSingleton.class.isAssignableFrom(klass)) {
            return true;
        }
        if (ImageSingletons.contains(HostedOptionValues.class)) {
            return SubstrateOptions.ApplicationLayerOnlySingletons.getValue().contains(klass.getName());
        }

        return false;
    }
}
