/*
Copyright (C) 2009 Apple Computer, Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:
1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY APPLE COMPUTER, INC. ``AS IS'' AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL APPLE COMPUTER, INC. OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY
OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

description("Tests calling WebGL APIs with objects from other contexts");

var contextA = create3DContext();
var contextB = create3DContext();
var programA = loadStandardProgram(contextA);
var programB = loadStandardProgram(contextB);
var shaderA = loadStandardVertexShader(contextA);
var shaderB = loadStandardVertexShader(contextB);
var textureA = contextA.createTexture();
var textureB = contextB.createTexture();
var frameBufferA = contextA.createFramebuffer();
var frameBufferB = contextB.createFramebuffer();
var renderBufferA = contextA.createRenderbuffer();
var renderBufferB = contextB.createRenderbuffer();
var locationA = contextA.getUniformLocation(programA, 'u_modelViewProjMatrix');
var locationB = contextB.getUniformLocation(programB, 'u_modelViewProjMatrix');


shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.compileShader(shaderB)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.linkProgram(programB)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.attachShader(programA, shaderB)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.attachShader(programB, shaderA)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.attachShader(programB, shaderB)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.detachShader(programA, shaderB)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.detachShader(programB, shaderA)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.detachShader(programB, shaderB)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.shaderSource(shaderB, 'foo')");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.bindAttribLocation(programB, 0, 'foo')");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.bindFramebuffer(contextA.FRAMEBUFFER, frameBufferB)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.bindRenderbuffer(contextA.RENDERBUFFER, renderBufferB)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.bindTexture(contextA.TEXTURE_2D, textureB)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.framebufferRenderbuffer(contextA.FRAMEBUFFER, contextA.DEPTH_ATTACHMENT, contextA.RENDERBUFFER, renderBufferB)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.framebufferTexture2D(contextA.FRAMEBUFFER, contextA.COLOR_ATTACHMENT0, contextA.TEXTURE_2D, textureB, 0)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.getProgramParameter(programB, 0)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.getProgramInfoLog(programB, 0)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.getShaderParameter(shaderB, 0)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.getShaderInfoLog(shaderB, 0)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.getShaderSource(shaderB)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.getUniform(programB, locationA)");
shouldGenerateGLError(contextA, contextA.INVALID_OPERATION, "contextA.getUniformLocation(programB, 'u_modelViewProjMatrix')");

successfullyParsed = true;
