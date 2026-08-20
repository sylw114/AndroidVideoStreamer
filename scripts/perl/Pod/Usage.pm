package Pod::Usage;

use strict;
use warnings;
use Exporter 'import';

our @EXPORT = qw(pod2usage);

# Git for Windows 附带的精简 Perl 没有 Pod::Usage。Tongsuo 生成的
# configdata.pm 只在显示帮助时调用它；构建过程仅要求模块可以加载。
sub pod2usage {
    my ($message) = @_;
    if (ref($message) eq 'HASH') {
        $message = $message->{-message};
    }
    die(($message // '请直接查看脚本内的 POD 帮助') . "\n");
}

1;
