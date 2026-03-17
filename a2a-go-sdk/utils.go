package a2a

import (
	"time"

	"github.com/google/uuid"
	"google.golang.org/protobuf/types/known/structpb"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// GenerateID 生成唯一ID
func GenerateID() string {
	return uuid.New().String()
}

// MapToStruct 将 Go map 转为 protobuf Struct
func MapToStruct(m map[string]any) (*structpb.Struct, error) {
	return structpb.NewStruct(m)
}

// StructToMap 将 protobuf Struct 转为 Go map
func StructToMap(s *structpb.Struct) map[string]any {
	if s == nil {
		return nil
	}
	return s.AsMap()
}

// NowTimestamp 获取当前时间的 protobuf Timestamp
func NowTimestamp() *timestamppb.Timestamp {
	return timestamppb.Now()
}

// TimestampToTime protobuf Timestamp 转 time.Time
func TimestampToTime(ts *timestamppb.Timestamp) time.Time {
	if ts == nil {
		return time.Time{}
	}
	return ts.AsTime()
}
