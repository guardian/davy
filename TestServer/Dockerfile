# Run from the root of the repository, mounting its Spec folder so the server checks requests
# against the current spec:
#   docker build -t davy-test-server TestServer
#   docker run --rm -p 8080:8080 -v "$PWD/Spec:/app/Spec:ro" davy-test-server
FROM node:26-slim

WORKDIR /app/TestServer
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY index.html ./
COPY src ./src

EXPOSE 8080
USER node
CMD ["node", "src/main.ts"]
