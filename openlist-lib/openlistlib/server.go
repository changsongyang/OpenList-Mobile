package openlistlib

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"syscall"
	"time"

	"github.com/OpenListTeam/OpenList/v4/openlistlib/internal"
	"github.com/OpenListTeam/OpenList/v4/cmd"
	"github.com/OpenListTeam/OpenList/v4/cmd/flags"
	"github.com/OpenListTeam/OpenList/v4/internal/bootstrap"
	"github.com/OpenListTeam/OpenList/v4/internal/conf"
	"github.com/OpenListTeam/OpenList/v4/pkg/utils"
	"github.com/OpenListTeam/OpenList/v4/server"
	"github.com/gin-gonic/gin"
	log "github.com/sirupsen/logrus"
)

type LogCallback interface {
	OnLog(level int16, time int64, message string)
}

type Event interface {
	OnStartError(t string, err string)
	OnShutdown(t string)
	OnProcessExit(code int)
}

var event Event
var logFormatter *internal.MyFormatter

// 添加全局的quit channel用于信号处理
var (
	quitChannel chan os.Signal
	serverMutex sync.Mutex
	isServerRunning bool
)

func Init(e Event, cb LogCallback) error {
	event = e
	cmd.Init()
	logFormatter = &internal.MyFormatter{
		OnLog: func(entry *log.Entry) {
			cb.OnLog(int16(entry.Level), entry.Time.UnixMilli(), entry.Message)
		},
	}
	if utils.Log == nil {
		return errors.New("utils.log is nil")
	} else {
		utils.Log.SetFormatter(logFormatter)
		utils.Log.ExitFunc = event.OnProcessExit
	}
	
	// 初始化信号处理
	if quitChannel == nil {
		quitChannel = make(chan os.Signal, 1)
		signal.Notify(quitChannel, syscall.SIGINT, syscall.SIGTERM)
		utils.Log.Println("Signal handler initialized")
	}
	
	return nil
}

var httpSrv, httpsSrv, unixSrv *http.Server

func listenAndServe(t string, srv *http.Server) {
	err := srv.ListenAndServe()
	if err != nil && err != http.ErrServerClosed {
		event.OnStartError(t, err.Error())
	} else {
		event.OnShutdown(t)
	}
}

func IsRunning(t string) bool {
	serverMutex.Lock()
	defer serverMutex.Unlock()
	
	switch t {
	case "http":
		return httpSrv != nil
	case "https":
		return httpsSrv != nil
	case "unix":
		return unixSrv != nil
	case "":
		// 默认检查整体服务状态
		return isServerRunning && (httpSrv != nil || httpsSrv != nil || unixSrv != nil)
	}

	return httpSrv != nil || httpsSrv != nil || unixSrv != nil
}

// Start starts the server
func Start() {
	serverMutex.Lock()
	if isServerRunning {
		utils.Log.Println("Server is already running")
		serverMutex.Unlock()
		return
	}
	isServerRunning = true
	serverMutex.Unlock()
	
	if conf.Conf.DelayedStart != 0 {
		utils.Log.Infof("delayed start for %d seconds", conf.Conf.DelayedStart)
		time.Sleep(time.Duration(conf.Conf.DelayedStart) * time.Second)
	}
	bootstrap.InitOfflineDownloadTools()
	bootstrap.LoadStorages()
	bootstrap.InitTaskManager()
	if !flags.Debug && !flags.Dev {
		gin.SetMode(gin.ReleaseMode)
	}
	r := gin.New()
	r.Use(gin.LoggerWithWriter(log.StandardLogger().Out), gin.RecoveryWithWriter(log.StandardLogger().Out))
	server.Init(r)

	if conf.Conf.Scheme.HttpPort != -1 {
		httpBase := fmt.Sprintf("%s:%d", conf.Conf.Scheme.Address, conf.Conf.Scheme.HttpPort)
		utils.Log.Infof("start HTTP server @ %s", httpBase)
		httpSrv = &http.Server{Addr: httpBase, Handler: r}
		go func() {
			listenAndServe("http", httpSrv)
			httpSrv = nil
		}()
	}
	if conf.Conf.Scheme.HttpsPort != -1 {
		httpsBase := fmt.Sprintf("%s:%d", conf.Conf.Scheme.Address, conf.Conf.Scheme.HttpsPort)
		utils.Log.Infof("start HTTPS server @ %s", httpsBase)
		httpsSrv = &http.Server{Addr: httpsBase, Handler: r}
		go func() {
			listenAndServe("https", httpsSrv)
			httpsSrv = nil
		}()
	}
	if conf.Conf.Scheme.UnixFile != "" {
		utils.Log.Infof("start unix server @ %s", conf.Conf.Scheme.UnixFile)
		unixSrv = &http.Server{Handler: r}
		go func() {
			listener, err := net.Listen("unix", conf.Conf.Scheme.UnixFile)
			if err != nil {
				//utils.Log.Fatalf("failed to listenAndServe unix: %+v", err)
				event.OnStartError("unix", err.Error())
			} else {
				// set socket file permission
				mode, err := strconv.ParseUint(conf.Conf.Scheme.UnixFilePerm, 8, 32)
				if err != nil {
					utils.Log.Errorf("failed to parse socket file permission: %+v", err)
				} else {
					err = os.Chmod(conf.Conf.Scheme.UnixFile, os.FileMode(mode))
					if err != nil {
						utils.Log.Errorf("failed to chmod socket file: %+v", err)
					}
				}
				err = unixSrv.Serve(listener)
				if err != nil && err != http.ErrServerClosed {
					event.OnStartError("unix", err.Error())
				}
			}

			unixSrv = nil
		}()
	}
	
	// 启动信号等待goroutine，模拟原本的main函数行为
	go func() {
		utils.Log.Println("Signal handler started, waiting for SIGTERM/SIGINT...")
		<-quitChannel // 等待信号
		utils.Log.Println("Received shutdown signal, initiating graceful shutdown...")
		performGracefulShutdown()
	}()
}

func shutdown(srv *http.Server, timeout time.Duration) error {
	if srv == nil {
		return nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	err := srv.Shutdown(ctx)

	return err
}

// performGracefulShutdown 执行优雅关闭，模拟原本server.go中的逻辑
func performGracefulShutdown() {
	serverMutex.Lock()
	defer serverMutex.Unlock()
	
	if !isServerRunning {
		utils.Log.Println("Server is not running, nothing to shutdown")
		return
	}
	
	utils.Log.Println("Performing graceful shutdown...")
	
	// 执行清理任务
	cmd.Release()
	
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	
	var wg sync.WaitGroup
	
	if httpSrv != nil {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := httpSrv.Shutdown(ctx); err != nil {
				utils.Log.Error("HTTP server shutdown err: ", err)
			} else {
				utils.Log.Println("HTTP server shutdown completed")
			}
			httpSrv = nil
		}()
	}
	
	if httpsSrv != nil {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := httpsSrv.Shutdown(ctx); err != nil {
				utils.Log.Error("HTTPS server shutdown err: ", err)
			} else {
				utils.Log.Println("HTTPS server shutdown completed")
			}
			httpsSrv = nil
		}()
	}
	
	if unixSrv != nil {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := unixSrv.Shutdown(ctx); err != nil {
				utils.Log.Error("Unix server shutdown err: ", err)
			} else {
				utils.Log.Println("Unix server shutdown completed")
			}
			unixSrv = nil
		}()
	}
	
	wg.Wait()
	isServerRunning = false
	utils.Log.Println("Graceful shutdown completed")
	
	// 通知Android端关闭已完成
	if event != nil {
		event.OnShutdown("graceful")
	}
}

// Shutdown 现在真正发送SIGTERM信号来触发优雅关闭
func Shutdown(timeout int64) (err error) {
	utils.Log.Println("Shutdown requested - sending SIGTERM signal...")
	
	serverMutex.Lock()
	running := isServerRunning
	serverMutex.Unlock()
	
	if !running {
		utils.Log.Println("Server is not running")
		return nil
	}
	
	// 发送SIGTERM信号到我们自己的signal channel
	// 这会触发在Start()中启动的信号等待goroutine
	select {
	case quitChannel <- syscall.SIGTERM:
		utils.Log.Println("SIGTERM signal sent successfully")
	default:
		utils.Log.Println("Signal channel is full or closed, performing direct shutdown")
		performGracefulShutdown()
	}
	
	// 等待关闭完成，最多等待指定的超时时间
	maxWait := time.Duration(timeout) * time.Millisecond
	if maxWait < 100*time.Millisecond {
		maxWait = 5 * time.Second // 默认5秒超时
	}
	
	waitStart := time.Now()
	for time.Since(waitStart) < maxWait {
		serverMutex.Lock()
		running := isServerRunning
		serverMutex.Unlock()
		
		if !running {
			utils.Log.Println("Shutdown completed successfully")
			return nil
		}
		
		time.Sleep(100 * time.Millisecond)
	}
	
	utils.Log.Println("Shutdown timeout reached, but process may still be completing")
	return nil
}
