FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ENV NG_BUILD_MAX_WORKERS=2
RUN npm test && npm run build
FROM nginx:1.28-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/voiceline/browser /usr/share/nginx/html
EXPOSE 80
