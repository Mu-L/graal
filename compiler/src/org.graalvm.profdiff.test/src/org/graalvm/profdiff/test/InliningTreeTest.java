/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.profdiff.test;

import org.graalvm.profdiff.core.TreeNode;
import org.graalvm.profdiff.core.inlining.InliningPath;
import org.graalvm.profdiff.core.inlining.InliningTree;
import org.graalvm.profdiff.core.inlining.InliningTreeNode;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class InliningTreeTest {
    /**
     * Tests that {@link org.graalvm.profdiff.core.inlining.InliningTree#findNodesAt(InliningPath)}
     * find all nodes on a path.
     *
     * The following inlining tree is tested:
     *
     * <pre>
     * Inlining tree
     *     a() at bci -1
     *         b() at bci 1
     *             d() at bci 3
     *         b() at bci 1
     *             d() at bci 3
     *         c() at bci 2
     *         (abstract) e() at bci 3
     *             f() at bci 1
     * </pre>
     */
    @Test
    public void findNodesAtPath() {
        InliningTreeNode a = new InliningTreeNode("a()", -1, true, null, false, null);
        InliningTreeNode b1 = new InliningTreeNode("b()", 1, true, null, false, null);
        InliningTreeNode d1 = new InliningTreeNode("d()", 3, true, null, false, null);
        InliningTreeNode b2 = new InliningTreeNode("b()", 1, true, null, false, null);
        InliningTreeNode d2 = new InliningTreeNode("d()", 3, true, null, false, null);
        InliningTreeNode c = new InliningTreeNode("c()", 2, true, null, false, null);
        InliningTreeNode e = new InliningTreeNode("e()", 3, false, null, true, null);
        InliningTreeNode f = new InliningTreeNode("f()", 1, true, null, false, null);

        a.addChild(b1);
        a.addChild(b2);
        a.addChild(c);
        a.addChild(e);
        b1.addChild(d1);
        b2.addChild(d2);
        e.addChild(f);

        InliningTree inliningTree = new InliningTree(a);
        assertEquals(List.of(), inliningTree.findNodesAt(InliningPath.EMPTY));
        assertEquals(List.of(a), inliningTree.findNodesAt(InliningPath.fromRootToNode(a)));
        assertEquals(List.of(b1, b2), inliningTree.findNodesAt(InliningPath.fromRootToNode(b1)));
        assertEquals(List.of(d1, d2), inliningTree.findNodesAt(InliningPath.fromRootToNode(d1)));
        assertEquals(List.of(c), inliningTree.findNodesAt(InliningPath.fromRootToNode(c)));
        assertEquals(List.of(f), inliningTree.findNodesAt(InliningPath.fromRootToNode(f)));
    }

    private static <T extends TreeNode<T>> List<T> treeInPreorder(T root) {
        List<T> preorder = new ArrayList<>();
        root.forEach(preorder::add);
        return preorder;
    }

    @Test
    public void sortInliningTree() {
        InliningTreeNode inliningTreeRoot = new InliningTreeNode("root", 0, true, null, false, null);
        InliningTreeNode method1 = new InliningTreeNode("method1", 1, true, null, false, null);
        InliningTreeNode method2 = new InliningTreeNode("method1", 2, true, null, false, null);
        InliningTreeNode method3 = new InliningTreeNode("method2", 2, true, null, false, null);
        InliningTreeNode method3First = new InliningTreeNode("method", 1, true, null, false, null);
        InliningTreeNode method3Second = new InliningTreeNode("method", 2, true, null, false, null);
        method3.addChild(method3Second);
        method3.addChild(method3First);
        InliningTreeNode method4 = new InliningTreeNode("method1", 3, true, null, false, null);
        InliningTreeNode method5 = new InliningTreeNode("method2", 3, false, null, false, null);
        InliningTreeNode method6 = new InliningTreeNode("method2", 3, true, null, false, null);
        inliningTreeRoot.addChild(method4);
        inliningTreeRoot.addChild(method6);
        inliningTreeRoot.addChild(method1);
        inliningTreeRoot.addChild(method5);
        inliningTreeRoot.addChild(method3);
        inliningTreeRoot.addChild(method2);

        InliningTree inliningTree = new InliningTree(inliningTreeRoot);
        List<InliningTreeNode> expected = List.of(inliningTreeRoot, method1, method2, method3, method3First, method3Second,
                        method4, method5, method6);
        inliningTree.sortInliningTree();
        List<InliningTreeNode> actual = treeInPreorder(inliningTree.getRoot());
        assertEquals(expected, actual);
    }
}
