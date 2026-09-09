#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
#

# Gotenberg drives headless Chromium, which shapes Khmer (and other complex scripts) correctly through HarfBuzz.
# The stock image ships no Khmer font, so a report rendered against it would come out blank; install one here.
FROM gotenberg/gotenberg:8

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-khmeros fonts-noto-core \
    && rm -rf /var/lib/apt/lists/*
# To use a font that is not packaged for Debian, drop the .ttf files next to this Dockerfile and instead of the
# apt-get above use:  COPY *.ttf /usr/share/fonts/truetype/custom/
USER gotenberg
