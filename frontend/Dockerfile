FROM --platform=$BUILDPLATFORM node:20-alpine AS build
WORKDIR /app
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
ARG BACKEND_HOST=localhost
COPY frontend/nginx.conf /etc/nginx/nginx.conf
RUN sed -i "s|__BACKEND_HOST__|${BACKEND_HOST}|g" /etc/nginx/nginx.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 9201
ENTRYPOINT ["nginx", "-g", "daemon off;"]
