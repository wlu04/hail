FROM {{ hail_ubuntu_image.image }}

# https://bugs.mysql.com/bug.php?id=105288&thanks=sub
RUN hail-apt-get-install xz-utils libncurses5 && \
    curl --remote-name https://downloads.mysql.com/archives/get/p/23/file/mysql-8.0.26-linux-glibc2.17-x86_64-minimal-rebuild.tar.xz && \
    mkdir -p /opt && \
    tar -vx -C /opt -f mysql-8.0.26-linux-glibc2.17-x86_64-minimal-rebuild.tar.xz && \
    ln -s /opt/mysql-8.0.26-linux-glibc2.17-x86_64-minimal-rebuild/bin/* /usr/bin/
